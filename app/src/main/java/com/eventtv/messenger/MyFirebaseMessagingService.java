package com.eventtv.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
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

        // WakeLock 획득 - Doze 모드에서도 소리/진동 실행 보장
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EventTV:FCMWakeLock"
            );
            wakeLock.acquire(5000L); // 최대 5초 유지
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

        // ── 1단계: 배지 전용 알림 업데이트 ──────────────────────────────────────
        {
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
                .setSilent(true)
                .setContentIntent(badgePendingIntent);

            manager.cancel(MainActivity.BADGE_NOTIFICATION_ID);
            manager.notify(MainActivity.BADGE_NOTIFICATION_ID, badgeBuilder.build());
        }

        // ── 2단계: 포그라운드 상태이면 팝업 알림 표시 안 함 ──────────────────────
        if (MainActivity.isInForeground) {
            return;
        }

        // ── 3단계: 소리 설정 확인 ────────────────────────────────────────────────
        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        boolean soundEnabled = prefs.getBoolean(AndroidBridge.KEY_NOTIFICATION_SOUND, true);
        int volumePct = prefs.getInt(AndroidBridge.KEY_NOTIFICATION_VOLUME, 67);
        float volumeFloat = volumePct / 100.0f;
        int vibDurationMs = prefs.getInt(AndroidBridge.KEY_VIBRATION_DURATION, 700);

        // ── 4단계: 알림음 재생 (Ringtone 방식 - Doze 모드에서도 안정적) ──────────
        if (soundEnabled) {
            try {
                // 볼륨 설정
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (audioManager != null) {
                    int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                    int targetVol = (int) Math.round(maxVol * volumeFloat);
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, targetVol, 0);
                }

                // Ringtone 방식: prepare() 불필요, 즉시 재생, Doze 모드 안정적
                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                if (soundUri == null) soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (soundUri != null) {
                    Ringtone ringtone = RingtoneManager.getRingtone(getApplicationContext(), soundUri);
                    if (ringtone != null) {
                        // Android 8.0+ 오디오 속성 설정
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ringtone.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                        }
                        ringtone.play();

                        // 2초 후 정지 (알림음이 너무 길게 재생되지 않도록)
                        final Ringtone finalRingtone = ringtone;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                if (finalRingtone.isPlaying()) finalRingtone.stop();
                            } catch (Exception ignored) {}
                        }, 2000L);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("EventTV", "알림음 재생 오류: " + e.getMessage());
            }
        }

        // ── 5단계: 진동 (Android 8+ 채널 진동 + 직접 진동 병행) ─────────────────
        if (vibDurationMs > 0) {
            try {
                long vd = vibDurationMs;
                long gap = Math.max(100, vd / 3);
                long[] vibrationPattern = {0, vd, gap, vd, gap, vd};

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+: VibratorManager 사용
                    VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                    if (vm != null) {
                        android.os.Vibrator v = vm.getDefaultVibrator();
                        if (v.hasVibrator()) {
                            v.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1));
                        }
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8~11: VibrationEffect
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        vibrator.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1));
                    }
                } else {
                    // Android 7 이하
                    Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        vibrator.vibrate(vibrationPattern, -1);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("EventTV", "진동 오류: " + e.getMessage());
            }
        }

        // ── 6단계: 팝업 알림 발행 ────────────────────────────────────────────────
        // 진동 설정에 따른 채널 선택
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
            .setContentIntent(pendingIntent);

        int popupNotificationId = (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
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
