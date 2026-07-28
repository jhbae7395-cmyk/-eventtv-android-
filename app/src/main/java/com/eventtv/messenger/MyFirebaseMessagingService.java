package com.eventtv.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
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

        // 배지 알림은 포그라운드/백그라운드 상관없이 항상 업데이트
        // (포그라운드 체크는 소리/진동/팝업 알림에만 적용)
        {
            NotificationManager badgeManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (badgeManager != null) {
                Intent badgeIntent = new Intent(this, MainActivity.class);
                badgeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int badgeFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
                PendingIntent badgePendingIntent = PendingIntent.getActivity(this, 1, badgeIntent, badgeFlags);
                String badgeChannelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? MainActivity.CHANNEL_VIB_OFF : MainActivity.CHANNEL_ID;
                NotificationCompat.Builder badgeBuilder = new NotificationCompat.Builder(this, badgeChannelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("EventTV 메신저")
                    .setContentText(badgeCount + "개의 안읽은 메시지")
                    .setAutoCancel(false)
                    .setOngoing(true)
                    .setNumber(badgeCount)
                    .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                    .setContentIntent(badgePendingIntent);
                badgeManager.cancel(MainActivity.BADGE_NOTIFICATION_ID);
                badgeManager.notify(MainActivity.BADGE_NOTIFICATION_ID, badgeBuilder.build());
            }
        }

        // 포그라운드 상태이면 소리/진동/팝업 알림 표시 안 함
        if (MainActivity.isInForeground) {
            return;
        }

        // 진동 시간 설정 확인 (SharedPreferences)
        SharedPreferences vibPrefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        int vibDurationMs = vibPrefs.getInt(AndroidBridge.KEY_VIBRATION_DURATION, 700);

        // Android 7 이하에서만 직접 진동 (Android 8+는 채널에서 처리)
        if (vibDurationMs > 0 && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            long vd = vibDurationMs;
            long gap = Math.max(100, vd / 3);
            long[] vibrationPattern = {0, vd, gap, vd, gap, vd};
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(vibrationPattern, -1);
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
        int volumePct = prefs.getInt(AndroidBridge.KEY_NOTIFICATION_VOLUME, 67); // 0~100
        float volumeFloat = volumePct / 100.0f;

        // 알림음 재생 (MediaPlayer 방식 - 설정된 볼륨 적용)
        if (soundEnabled) {
            try {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (audioManager != null) {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                    int targetVol = (int) Math.round(maxVol * volumeFloat);
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetVol, 0);
                }
                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (soundUri == null) soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (soundUri == null) soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

                final MediaPlayer mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                mediaPlayer.setDataSource(getApplicationContext(), soundUri);
                mediaPlayer.setLooping(false);
                mediaPlayer.setVolume(volumeFloat, volumeFloat);
                mediaPlayer.prepare();
                mediaPlayer.start();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                        mediaPlayer.release();
                    } catch (Exception ignored) {}
                }, 1000L);
            } catch (Exception e) {
                android.util.Log.e("EventTV", "알림음 재생 오류: " + e.getMessage());
            }
        }

        // 알림 표시
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
            .setAutoCancel(false)           // 메시지를 읽기 전까지 알림 유지
            .setOngoing(true)               // 지속 알림: 앱을 열어도 자동 제거 안 함 → 배지 유지
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(badgeCount)           // 배지 숫자 (안 읽은 메시지 수)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)  // 삼성 One UI 배지 강제 표시
            .setContentIntent(pendingIntent);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            // 기존 알림 취소 후 재발행: setOngoing(true) 알림은 notify()만으로 setNumber()가 갱신 안 됨
            // cancel → notify 순서로 배지 숫자를 강제 갱신
            manager.cancel(MainActivity.BADGE_NOTIFICATION_ID);
            manager.notify(MainActivity.BADGE_NOTIFICATION_ID, builder.build());
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
