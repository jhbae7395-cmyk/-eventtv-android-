package com.eventtv.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;

public class AndroidBridge {
    private final Context context;

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
