package com.eventtv.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;

public class AndroidBridge {
    private final Context context;
    static final String PREFS_NAME = "eventtv_prefs";
    static final String KEY_EMPLOYEE_ID = "employee_id";  // employeeId 저장 (숫자)

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
