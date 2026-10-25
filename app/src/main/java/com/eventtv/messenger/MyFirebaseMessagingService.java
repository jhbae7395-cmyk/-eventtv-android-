package com.eventtv.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
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

        // Doze 중 수신 처리 시간이 부족해지는 것을 방지한다. 알림 자체의 소리·진동은
        // Android 시스템 채널이 담당하며, WakeLock으로 설정을 우회하지는 않는다.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EventTV:FCMWakeLock"
            );
            wakeLock.acquire(5000L);
        }

        try {
            handleMessage(remoteMessage);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private void handleMessage(RemoteMessage remoteMessage) {
        String title = "EventTV 메신저";
        String body = "새 메시지가 도착했습니다.";
        String url = "https://eventtv-gpdc5ulb.manus.space/messenger";

        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (remoteMessage.getNotification().getBody() != null) {
                body = remoteMessage.getNotification().getBody();
            }
        }
        if (remoteMessage.getData().containsKey("title")) {
            title = remoteMessage.getData().get("title");
        }
        if (remoteMessage.getData().containsKey("body")) {
            body = remoteMessage.getData().get("body");
        }
        if (remoteMessage.getData().containsKey("url")) {
            url = remoteMessage.getData().get("url");
        }

        int badgeCount = 1;
        if (remoteMessage.getData().containsKey("badge")) {
            try {
                badgeCount = Integer.parseInt(remoteMessage.getData().get("badge"));
                if (badgeCount < 1) badgeCount = 1;
            } catch (NumberFormatException ignored) {
                // 서버값 오류는 기본 배지 수로 안전하게 처리한다.
            }
        }

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        // 앱이 실제 포그라운드이면 알림·배지·소리 모두 표시하지 않는다.
        // 서버 포그라운드 상태가 갱신되기 전 도착한 FCM도 이 앱 단의 검증으로 막는다.
        if (MainActivity.isInForeground) return;

        // 새 APK를 설치한 뒤 Activity가 아직 열리지 않았더라도 FCM 서비스가 v4 채널을 만든다.
        MainActivity.ensureNotificationChannels(this);

        // Android 알림 권한이나 앱 전체 알림이 꺼진 상태에서는 소리·진동을 우회하지 않는다.
        if (!MainActivity.canDeliverMessageNotification(this, null)) {
            android.util.Log.w("EventTV", "[FCM] Android 알림 권한 또는 앱 알림이 비활성화되어 표시하지 않음");
            return;
        }

        // 배지 전용 알림: 실제 총 미읽음 수만 단일 고정 알림으로 표시한다.
        Intent badgeIntent = new Intent(this, MainActivity.class);
        badgeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int badgeFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent badgePendingIntent = PendingIntent.getActivity(this, 1, badgeIntent, badgeFlags);
        String badgeChannelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? MainActivity.CHANNEL_BADGE : MainActivity.CHANNEL_ID;
        NotificationCompat.Builder badgeBuilder = new NotificationCompat.Builder(this, badgeChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("EventTV 메신저")
            .setContentText(badgeCount + "개의 안읽은 메시지")
            .setAutoCancel(false)
            .setOngoing(true)
            .setNumber(badgeCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSilent(true)
            .setContentIntent(badgePendingIntent);
        manager.cancel(MainActivity.BADGE_NOTIFICATION_ID);
        manager.notify(MainActivity.BADGE_NOTIFICATION_ID, badgeBuilder.build());

        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        boolean soundEnabled = prefs.getBoolean(AndroidBridge.KEY_NOTIFICATION_SOUND, true);
        int vibrationDurationMs = prefs.getInt(AndroidBridge.KEY_VIBRATION_DURATION, 700);

        // Android 8.0 이상에서는 NotificationChannel만이 소리·진동을 결정한다.
        // 앱이 Ringtone/Vibrator 또는 기기 전체 알림 볼륨을 직접 변경하지 않아 기종별
        // 무음·중복 재생과 사용자 시스템 설정 변경을 막는다.
        String selectedChannelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? MainActivity.getMessageChannelId(soundEnabled, vibrationDurationMs)
            : MainActivity.CHANNEL_ID;

        if (!MainActivity.canDeliverMessageNotification(this, selectedChannelId)) {
            android.util.Log.w("EventTV", "[FCM] 선택한 메시지 채널이 비활성화되어 표시하지 않음");
            return;
        }

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
            .setContentIntent(pendingIntent);

        int popupNotificationId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        manager.notify(popupNotificationId, builder.build());
    }

    @Override
    public void onNewToken(String newToken) {
        super.onNewToken(newToken);
        android.util.Log.d("EventTV", "[FCM] 토큰 갱신 수신");

        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        String employeeIdStr = prefs.getString(AndroidBridge.KEY_EMPLOYEE_ID, null);
        String employeeToken = prefs.getString(AndroidBridge.KEY_EMPLOYEE_TOKEN, null);
        if (employeeIdStr == null || employeeIdStr.isEmpty() || employeeToken == null || employeeToken.isEmpty()) {
            android.util.Log.w("EventTV", "[FCM] onNewToken: 로그인 인증 정보 없음 - 다음 앱 실행 시 재등록");
            return;
        }

        final String fcmToken = newToken;
        final String token = employeeToken;
        new Thread(() -> {
            try {
                JSONObject inputJson = new JSONObject();
                inputJson.put("employeeToken", token);
                inputJson.put("fcmToken", fcmToken);
                JSONObject item = new JSONObject();
                item.put("json", inputJson);
                JSONObject requestBody = new JSONObject();
                requestBody.put("0", item);

                URL apiUrl = new URL(REGISTER_URL);
                HttpURLConnection connection = (HttpURLConnection) apiUrl.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bodyBytes);
                    output.flush();
                }
                int responseCode = connection.getResponseCode();
                android.util.Log.d("EventTV", "[FCM] onNewToken 서버 업데이트 완료: HTTP " + responseCode);
                connection.disconnect();
            } catch (Exception error) {
                android.util.Log.e("EventTV", "[FCM] onNewToken 서버 업데이트 실패", error);
            }
        }).start();
    }
}
