package com.eventtv.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AndroidBridge {
    private final Context context;
    static final String PREFS_NAME = "eventtv_prefs";
    static final String KEY_EMPLOYEE_ID = "employee_id";  // employeeId 저장 (숫자)
    static final String KEY_NOTIFICATION_SOUND = "notification_sound";  // 알림 소리 켜기/끄기
    static final String KEY_NOTIFICATION_VOLUME = "notification_volume"; // 알림 볼륨 0~100
    static final String KEY_VIBRATION_DURATION = "vibration_duration"; // 진동 시간 ms (0=진동 끄기)

    public AndroidBridge(Context context) {
        this.context = context;
    }

    /**
     * Android 공유 메뉴에서 전달된 파일 수를 웹 메신저가 채팅방 선택 후 확인한다.
     */
    @JavascriptInterface
    public int getPendingSharedFileCount() {
        if (context instanceof MainActivity) {
            return ((MainActivity) context).getPendingSharedFileCount();
        }
        return 0;
    }

    /**
     * 메신저 이미지 URL을 내려받아 Android 시스템 공유 창으로 전달한다.
     * WebView의 Web Share API 지원 여부와 무관하게 갤러리·메신저 등 외부 앱에 이미지 파일을 공유한다.
     */
    @JavascriptInterface
    public void shareImage(String imageUrl, String fileName) {
        if (imageUrl == null || !imageUrl.startsWith("http")) return;

        new Thread(() -> {
            try {
                URL url = new URL(imageUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "EventTV Messenger");

                String contentType = connection.getContentType();
                if (contentType == null || !contentType.startsWith("image/")) {
                    contentType = "image/*";
                }

                File shareDirectory = new File(context.getCacheDir(), "shared_media");
                if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
                    throw new IllegalStateException("공유 폴더를 만들 수 없습니다.");
                }

                String safeName = sanitizeSharedImageName(fileName, contentType);
                File sharedImage = new File(shareDirectory, System.currentTimeMillis() + "_" + safeName);

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(sharedImage)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                } finally {
                    connection.disconnect();
                }

                Uri sharedUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    sharedImage
                );
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(contentType);
                shareIntent.putExtra(Intent.EXTRA_STREAM, sharedUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                shareIntent.setClipData(ClipData.newRawUri("EventTV 이미지", sharedUri));
                openShareChooser(shareIntent);
            } catch (Exception error) {
                // 다운로드에 실패한 경우에도 이미지 URL을 외부 앱으로 전달할 수 있도록 텍스트 공유로 전환한다.
                Intent linkShareIntent = new Intent(Intent.ACTION_SEND);
                linkShareIntent.setType("text/plain");
                linkShareIntent.putExtra(Intent.EXTRA_TEXT, imageUrl);
                openShareChooser(linkShareIntent);
                android.util.Log.e("EventTV", "이미지 공유 파일 준비 실패", error);
            }
        }).start();
    }

    private String sanitizeSharedImageName(String fileName, String contentType) {
        String safeName = fileName == null ? "" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) safeName = "eventtv_image";
        if (!safeName.contains(".")) {
            if ("image/png".equals(contentType)) safeName += ".png";
            else if ("image/webp".equals(contentType)) safeName += ".webp";
            else if ("image/gif".equals(contentType)) safeName += ".gif";
            else safeName += ".jpg";
        }
        return safeName;
    }

    private void openShareChooser(Intent shareIntent) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent chooser = Intent.createChooser(shareIntent, "이미지 공유");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        });
    }

    @JavascriptInterface
    public void vibrate() {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] pattern = {0, 300, 100, 300};
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(new long[]{0, 300, 100, 300}, -1);
            }
        }
    }

    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("EventTV", message);
    }

    /**
     * 알림 소리 켜기/끄기 설정 반환 (기본값: true)
     */
    @JavascriptInterface
    public boolean getNotificationSound() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_NOTIFICATION_SOUND, true);
    }

    /**
     * 알림 소리 켜기/끄기 설정 저장
     * @param enabled true=소리 켜기, false=소리 끄기
     */
    @JavascriptInterface
    public void setNotificationSound(boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, enabled).apply();
        android.util.Log.d("EventTV", "[설정] 알림 소리: " + (enabled ? "켜기" : "끄기"));
    }

    /**
     * 알림 볼륨 반환 (0~100, 기본값: 67)
     */
    @JavascriptInterface
    public int getNotificationVolume() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_NOTIFICATION_VOLUME, 67);
    }

    /**
     * 알림 볼륨 저장 (0~100)
     * @param volume 볼륨 값 (0=무음, 100=최대)
     */
    @JavascriptInterface
    public void setNotificationVolume(int volume) {
        if (volume < 0) volume = 0;
        if (volume > 100) volume = 100;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_NOTIFICATION_VOLUME, volume).apply();
        android.util.Log.d("EventTV", "[설정] 알림 볼륨: " + volume);
    }

    /**
     * 진동 시간 반환 (ms, 0=진동 끄기, 기본값: 700)
     */
    @JavascriptInterface
    public int getVibrationDuration() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_VIBRATION_DURATION, 700);
    }

    /**
     * 진동 시간 저장 (ms)
     * @param durationMs 0=진동 끄기, 300=짧게, 700=기본, 1500=길게
     */
    @JavascriptInterface
    public void setVibrationDuration(int durationMs) {
        if (durationMs < 0) durationMs = 0;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_VIBRATION_DURATION, durationMs).apply();
        android.util.Log.d("EventTV", "[설정] 진동 시간: " + durationMs + "ms");
    }

    /**
     * 웹 JS에서 직접 배지 숫자 저장 (참조용)
     * FCM 알림이 배지 역할 담당하므로 사일런트 알림 발행 안 함
     */
    @JavascriptInterface
    public void applyBadgeCount(int count) {
        MainActivity.currentUnreadCount = count;
        android.util.Log.d("EventTV", "[배지] 미읽음 수 저장: " + count);
    }

    /**
     * 웹 JS에서 배지 제거 - 메시지를 모두 읽었을 때 호출
     */
    @JavascriptInterface
    public void clearBadgeCount() {
        MainActivity.currentUnreadCount = 0;
        android.util.Log.d("EventTV", "[배지] 제거 (clearBadgeCount)");
        // ShortcutBadger로 아이콘 배지 0으로 초기화
        try {
            me.leolin.shortcutbadger.ShortcutBadger.removeCount(context);
        } catch (Exception ignored) {}
        // Samsung One UI는 남아 있는 개별 FCM 알림도 앱 아이콘 배지에 합산한다.
        // 실제 미읽음 수가 0일 때 EventTV의 모든 알림을 정리해야 아이콘 배지도 정확히 사라진다.
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancelAll();
            }
        } catch (Exception ignored) {}
    }

    /**
     * 소켓 badge_sync / totalUnread 변경 시 호출 - 앱 내부 미읽음 수와 홈 화면 배지 항상 동일하게 유지
     */
    @JavascriptInterface
    public void setBadgeCount(int count) {
        MainActivity.currentUnreadCount = count;
        android.util.Log.d("EventTV", "[배지] setBadgeCount: " + count);
        // 1) ShortcutBadger: 일부 런처에서 직접 배지 표시
        try {
            if (count > 0) {
                me.leolin.shortcutbadger.ShortcutBadger.applyCount(context, count);
            } else {
                me.leolin.shortcutbadger.ShortcutBadger.removeCount(context);
            }
        } catch (Exception ignored) {}
        // 2) 삼성 One UI 등 알림 기반 배지 런처 대응: 배지 전용 알림 갱신
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (count <= 0) {
                // One UI는 남아 있는 개별 FCM 알림도 배지에 합산하므로 모두 정리한다.
                nm.cancelAll();
            } else {
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    context, 1,
                    new android.content.Intent(context, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT
                );
                String channelId = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? MainActivity.CHANNEL_BADGE : MainActivity.CHANNEL_ID;
                androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("EventTV 메신저")
                        .setContentText(count + "개의 안읽은 메시지")
                        .setNumber(count)
                        .setBadgeIconType(androidx.core.app.NotificationCompat.BADGE_ICON_SMALL)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setSilent(true)
                        .setContentIntent(pi);
                nm.cancel(MainActivity.BADGE_NOTIFICATION_ID);
                nm.notify(MainActivity.BADGE_NOTIFICATION_ID, builder.build());
            }
        } catch (Exception ignored) {}
    }

    /**
     * 서버에서 전체 미읽음 메시지 수 조회 (미읽음 수 저장용)
     * 사일런트 알림 발행 안 함 - FCM 알림이 배지 역할 담당
     */
    @JavascriptInterface
    public void updateBadgeFromServer(String employeeToken) {
        if (employeeToken == null || employeeToken.isEmpty()) return;
        new Thread(() -> {
            try {
                String apiUrl = "https://eventtv-gpdc5ulb.manus.space/api/trpc/messenger.getTotalUnread?batch=1&input=" +
                    java.net.URLEncoder.encode("{\"0\":{\"json\":{\"employeeToken\":\"" + employeeToken + "\"}}}" , "UTF-8");
                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    String body = sb.toString();
                    org.json.JSONArray arr = new org.json.JSONArray(body);
                    int total = arr.getJSONObject(0)
                        .getJSONObject("result")
                        .getJSONObject("data")
                        .getJSONObject("json")
                        .getInt("total");
                    android.util.Log.d("EventTV", "[배지] 서버 미읽음 수: " + total);
                    // 서버 값만 단일 진실의 원천으로 사용한다.
                    // ShortcutBadger만 갱신하면 One UI의 알림 기반 배지가 남을 수 있으므로
                    // 고정 배지 알림까지 함께 갱신/취소하는 setBadgeCount를 사용한다.
                    setBadgeCount(total);
                }
                conn.disconnect();
            } catch (Exception e) {
                android.util.Log.e("EventTV", "[배지] 서버 조회 실패: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 직원 로그인 시 호출 - employeeId를 SharedPreferences에 저장
     * FCM 토큰 갱신(onNewToken) 시 서버 업데이트에 사용
     * @param employeeId 직원 ID (숫자 문자열)
     */
    @JavascriptInterface
    public void onEmployeeLogin(String employeeId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_EMPLOYEE_ID, employeeId).apply();
            android.util.Log.d("EventTV", "[FCM] onEmployeeLogin - employeeId: " + employeeId);
        } catch (Exception e) {
            android.util.Log.e("EventTV", "onEmployeeLogin error: " + e.getMessage());
        }
    }

    /**
     * 직원 로그아웃 시 호출 - SharedPreferences에서 employeeId 제거
     * @param employeeId 직원 ID (숫자 문자열)
     */
    @JavascriptInterface
    public void onEmployeeLogout(String employeeId) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_EMPLOYEE_ID).apply();
            android.util.Log.d("EventTV", "[FCM] onEmployeeLogout - employeeId: " + employeeId);
        } catch (Exception e) {
            android.util.Log.e("EventTV", "onEmployeeLogout error: " + e.getMessage());
        }
    }

    /**
     * 클립보드에서 텍스트 읽기
     * @return 클립보드 텍스트, 없으면 빈 문자열
     */
    @JavascriptInterface
    public String getClipboardText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).coerceToText(context);
                    if (text != null) {
                        return text.toString();
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("EventTV", "getClipboardText error: " + e.getMessage());
        }
        return "";
    }

    /**
     * 클립보드에 텍스트 쓰기
     */
    @JavascriptInterface
    public void setClipboardText(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("EventTV", text);
                clipboard.setPrimaryClip(clip);
            }
        } catch (Exception e) {
            android.util.Log.e("EventTV", "setClipboardText error: " + e.getMessage());
        }
    }

    /**
     * 클립보드 타입 확인
     * @return "image" | "text" | "empty"
     */
    @JavascriptInterface
    public String getClipboardType() {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getUri() != null) {
                        String mimeType = clip.getDescription().getMimeType(0);
                        if (mimeType != null && mimeType.startsWith("image/")) {
                            return "image";
                        }
                    }
                    if (item.getText() != null && item.getText().length() > 0) {
                        return "text";
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("EventTV", "getClipboardType error: " + e.getMessage());
        }
        return "empty";
    }

    /**
     * 클립보드 이미지 URI 반환 (content:// URI)
     */
    @JavascriptInterface
    public String getClipboardImageUri() {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    ClipData.Item item = clip.getItemAt(0);
                    if (item.getUri() != null) {
                        return item.getUri().toString();
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("EventTV", "getClipboardImageUri error: " + e.getMessage());
        }
        return "";
    }
}
