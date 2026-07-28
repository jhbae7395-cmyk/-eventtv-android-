package com.eventtv.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
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
        // FCM 배지 알림도 취소
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(MainActivity.BADGE_NOTIFICATION_ID);
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
                    MainActivity.currentUnreadCount = total;
                    // 사일런트 알림 발행 안 함 - FCM 알림이 배지 역할 담당
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
