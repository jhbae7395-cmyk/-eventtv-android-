package com.eventtv.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.Ringtone;
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

        // 배지 숫자
        int badgeCount = 1;
        if (remoteMessage.getData().containsKey("badge")) {
            try {
                badgeCount = Integer.parseInt(remoteMessage.getData().get("badge"));
                if (badgeCount < 1) badgeCount = 1;
            } catch (NumberFormatException ignored) {}
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        // ── 0단계: ShortcutBadger로 런처 아이콘 배지 갱신 ──────────────────────
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
        if (MainActivity.isInForeground) {
            android.util.Log.d("EventTV", "[FCM] 포그라운드 상태 - 팝업/소리/진동 생략");
            return;
        }

        // ── 2단계: 설정 읽기 ─────────────────────────────────────────────────────
        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        boolean soundEnabled = prefs.getBoolean(AndroidBridge.KEY_NOTIFICATION_SOUND, true);
        int volumePct = prefs.getInt(AndroidBridge.KEY_NOTIFICATION_VOLUME, 67); // 0~100
        int vibDurationMs = prefs.getInt(AndroidBridge.KEY_VIBRATION_DURATION, 700);

        // ── 3단계: 소리 직접 재생 (Ringtone + AudioManager 볼륨 제어) ────────────
        // Ringtone은 백그라운드 Service에서 안정적으로 동작
        // AudioManager로 볼륨 임시 변경 → 재생 → 원래 볼륨 복원
        if (soundEnabled && volumePct > 0) {
            playNotificationSound(volumePct);
        }

        // ── 4단계: 진동 직접 처리 ────────────────────────────────────────────────
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
                android.util.Log.d("EventTV", "[진동] 재생: " + vibDurationMs + "ms");
            }
        }

        // ── 5단계: 채널 선택 (Android 8.0+) ─────────────────────────────────────
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

        // ── 6단계: 팝업 알림 발행 (소리/진동은 위에서 직접 처리) ─────────────────
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("url", url);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_ONE_SHOT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, selectedChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(badgeCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSound(null)       // 소리는 Ringtone이 직접 재생
            .setVibrate(null)     // 진동은 Vibrator가 직접 처리
            .setContentIntent(pendingIntent);

        int channelIdInt = 0;
        if (remoteMessage.getData().containsKey("channelId")) {
            try {
                channelIdInt = Integer.parseInt(remoteMessage.getData().get("channelId"));
            } catch (NumberFormatException ignored) {}
        }
        int popupNotificationId = channelIdInt > 0 ? channelIdInt : (int) (System.currentTimeMillis() % 100000);
        manager.notify(popupNotificationId, builder.build());
        android.util.Log.d("EventTV", "[FCM] 팝업 알림 발행 완료 - id:" + popupNotificationId);
    }

    /**
     * Ringtone + AudioManager 볼륨 제어로 알림음 재생
     * - AudioManager로 알림 볼륨을 사용자 설정값으로 임시 변경
     * - Ringtone.play()로 재생 (백그라운드 Service에서 안정적)
     * - Android 9(P)+에서는 Ringtone.setVolume()으로 직접 볼륨 설정 가능
     */
    private void playNotificationSound(int volumePct) {
        try {
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone ringtone = RingtoneManager.getRingtone(this, soundUri);
            if (ringtone == null) {
                android.util.Log.e("EventTV", "[소리] Ringtone null - 재생 불가");
                return;
            }

            float vol = Math.max(0f, Math.min(1f, volumePct / 100.0f));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9+: Ringtone.setVolume()으로 직접 볼륨 설정
                ringtone.setVolume(vol);
                ringtone.play();
                android.util.Log.d("EventTV", "[소리] Ringtone.setVolume(" + vol + ") 재생");
            } else {
                // Android 8 이하: AudioManager로 시스템 알림 볼륨 임시 변경 후 재생
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                int maxVol = audioManager != null ? audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) : 7;
                int originalVol = audioManager != null ? audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) : maxVol;
                int targetVol = Math.round(vol * maxVol);

                if (audioManager != null) {
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetVol, 0);
                }
                ringtone.play();
                android.util.Log.d("EventTV", "[소리] AudioManager 볼륨 " + targetVol + "/" + maxVol + " 재생");

                // 재생 후 원래 볼륨 복원 (3초 후)
                final AudioManager am = audioManager;
                final int origVol = originalVol;
                new Thread(() -> {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    if (am != null) {
                        am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, origVol, 0);
                    }
                }).start();
            }
        } catch (Exception e) {
            android.util.Log.e("EventTV", "[소리] 재생 오류: " + e.getMessage());
        }
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
