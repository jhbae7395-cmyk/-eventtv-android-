package com.eventtv.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import me.leolin.shortcutbadger.ShortcutBadger;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String REGISTER_URL =
        "https://eventtv-gpdc5ulb.manus.space/api/trpc/messenger.registerFcmToken?batch=1";

    // 팝업 알림 ID: 매번 새로운 ID 사용 (System.currentTimeMillis() 기반)
    // BADGE_NOTIFICATION_ID(99999)는 배지 전용으로만 사용 → 충돌 없음

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "EventTV 메신저";
        String body = "새 메시지가 도착했습니다.";
        String url = "https://eventtv-gpdc5ulb.manus.space/messenger";

        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null)
                title = remoteMessage.getNotification().getTitle();
            if (remoteMessage.getNotification().getBody() != null)
                body = remoteMessage.getNotification().getBody();
        }
        if (remoteMessage.getData().containsKey("title"))
            title = remoteMessage.getData().get("title");
        if (remoteMessage.getData().containsKey("body"))
            body = remoteMessage.getData().get("body");
        if (remoteMessage.getData().containsKey("url"))
            url = remoteMessage.getData().get("url");

        // 배지 숫자 (서버에서 전달한 전체 읽지 않은 메시지 수)
        int badgeCount = 1;
        if (remoteMessage.getData().containsKey("badge")) {
            try {
                badgeCount = Integer.parseInt(remoteMessage.getData().get("badge"));
                if (badgeCount < 1) badgeCount = 1;
            } catch (NumberFormatException ignored) {}
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        // ── 0단계: ShortcutBadger로 런처 아이콘 배지 직접 갱신 ─────────────────
        // FCM 수신 즉시 런처 배지 숫자 업데이트 (앱 종료/백그라운드 모두 동작)
        try {
            if (badgeCount > 0) {
                ShortcutBadger.applyCount(this, badgeCount);
            } else {
                ShortcutBadger.removeCount(this);
            }
            android.util.Log.d("EventTV", "[배지] ShortcutBadger 업데이트: " + badgeCount);
        } catch (Exception e) {
            android.util.Log.e("EventTV", "[배지] ShortcutBadger 오류: " + e.getMessage());
        }

        // ── 1단계: 포그라운드 상태이면 팝업 알림 표시 안 함 ──────────────────────
        // 배지 전용 알림(ID=99999) 제거 → ShortcutBadger만으로 런처 배지 제어
        // (배지 전용 알림이 런처 배지 카운트에 +1로 포함되는 문제 해결)
        if (MainActivity.isInForeground) {
            return;
        }

        // ── 3단계: 백그라운드일 때만 팝업 알림 발행 ─────────────────────────────
        // 팝업 알림은 배지 알림과 완전히 다른 ID 사용 (System.currentTimeMillis())
        // → BADGE_NOTIFICATION_ID와 충돌 없음 → 배지 깜빡임 없음

        // 진동 시간 설정 확인 (SharedPreferences)
        SharedPreferences vibPrefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        int vibDurationMs = vibPrefs.getInt(AndroidBridge.KEY_VIBRATION_DURATION, 700);

        // 진동 직접 처리 (Android 7 이하 + Android 8+ 모두)
        // Android 8+에서 채널 소리가 null이면 진동도 억제되는 기기가 있어 직접 처리
        if (vibDurationMs > 0) {
            long vd = vibDurationMs;
            long gap = Math.max(100, vd / 3);
            long[] vibrationPattern = {0, vd, gap, vd, gap, vd};
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1));
                } else {
                    vibrator.vibrate(vibrationPattern, -1);
                }
            }
        }

        // 진동 설정에 따른 채널 선택 (Android 8.0+)
        String selectedChannelId;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibDurationMs <= 0) {
                selectedChannelId = MainActivity.CHANNEL_VIB_OFF;
            } else if (vibDurationMs <= 300) {
                selectedChannelId = MainActivity.CHANNEL_VIB_SHORT;
            } else if (vibDurationMs <= 700) {
                selectedChannelId = MainActivity.CHANNEL_VIB_DEFAULT;
            } else {
                selectedChannelId = MainActivity.CHANNEL_VIB_LONG;
            }
        } else {
            selectedChannelId = MainActivity.CHANNEL_ID;
        }

        // 알림 소리 설정 확인 (SharedPreferences)
        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        boolean soundEnabled = prefs.getBoolean(AndroidBridge.KEY_NOTIFICATION_SOUND, true);
        // 볼륨은 AndroidBridge.setNotificationVolume() 저장 시 AudioManager로 직접 적용됨
        // 여기서는 채널 소리 설정만 사용 (채널이 소리 재생 담당)

        // 팝업 알림 발행 (배지 알림과 다른 ID 사용)
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_ONE_SHOT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        // 소리는 MediaPlayer가 직접 재생하므로 알림 빌더에서는 소리 비활성화
        // (Android 8+에서 채널 소리와 MediaPlayer가 중복 재생되는 문제 방지)
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, selectedChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)           // 탭하면 팝업 알림 자동 제거 (배지 알림은 별도 유지)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(badgeCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSound(soundEnabled ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : null)
            .setContentIntent(pendingIntent);

        // 팝업 알림 ID: channelId 기반으로 고정 → 같은 채널의 새 메시지는 이전 알림을 덮어씀
        // → 알림 센터에 채널 수만큼만 쌓임 → Samsung One UI 배지 숫자 정확
        int channelIdInt = 0;
        if (remoteMessage.getData().containsKey("channelId")) {
            try {
                channelIdInt = Integer.parseInt(remoteMessage.getData().get("channelId"));
            } catch (NumberFormatException ignored) {}
        }
        // channelId가 0이면 시간 기반 ID 사용 (fallback)
        int popupNotificationId = channelIdInt > 0 ? channelIdInt : (int) (System.currentTimeMillis() % 100000);
        manager.notify(popupNotificationId, builder.build());
    }

    @Override
    public void onNewToken(String newToken) {
        super.onNewToken(newToken);
        android.util.Log.d("EventTV", "[FCM] 토큰 갱신: " + newToken.substring(0, Math.min(20, newToken.length())) + "...");

        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        String employeeIdStr = prefs.getString(AndroidBridge.KEY_EMPLOYEE_ID, null);
        if (employeeIdStr == null || employeeIdStr.isEmpty()) {
            android.util.Log.w("EventTV", "[FCM] onNewToken: employeeId 없음 - 서버 업데이트 스킵");
            return;
        }
        int employeeId;
        try {
            employeeId = Integer.parseInt(employeeIdStr);
        } catch (NumberFormatException e) {
            android.util.Log.e("EventTV", "[FCM] onNewToken: employeeId 파싱 실패: " + employeeIdStr);
            return;
        }

        final int empId = employeeId;
        final String fcmToken = newToken;
        new Thread(() -> {
            try {
                JSONObject inputJson = new JSONObject();
                inputJson.put("employeeId", empId);
                inputJson.put("fcmToken", fcmToken);
                JSONObject item = new JSONObject();
                item.put("json", inputJson);
                JSONObject batchBody = new JSONObject();
                batchBody.put("0", item);

                URL apiUrl = new URL(REGISTER_URL);
                HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                byte[] bodyBytes = batchBody.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();
                int responseCode = conn.getResponseCode();
                android.util.Log.d("EventTV", "[FCM] onNewToken 서버 업데이트 완료: HTTP " + responseCode + " empId=" + empId);
                conn.disconnect();
            } catch (Exception e) {
                android.util.Log.e("EventTV", "[FCM] onNewToken 서버 업데이트 실패: " + e.getMessage());
            }
        }).start();
    }
}
