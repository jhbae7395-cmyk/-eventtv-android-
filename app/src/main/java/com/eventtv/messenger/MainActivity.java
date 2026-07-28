package com.eventtv.messenger;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.messaging.FirebaseMessaging;
import me.leolin.shortcutbadger.ShortcutBadger;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    public static final String CHANNEL_ID = "eventtv_messages_v4";
    public static final String CHANNEL_ID_FOREGROUND = "eventtv_messages_fg";

    // 진동별 채널 ID (v6: 소리 재활성화 - 볼륨은 AndroidBridge.setNotificationVolume()이 AudioManager로 직접 제어)
    public static final String CHANNEL_VIB_OFF     = "eventtv_vib_off_v6";     // 진동 없음
    public static final String CHANNEL_VIB_SHORT   = "eventtv_vib_short_v6";   // 짧게 300ms
    public static final String CHANNEL_VIB_DEFAULT = "eventtv_vib_default_v6"; // 기본 700ms
    public static final String CHANNEL_VIB_LONG    = "eventtv_vib_long_v6";    // 길게 1500ms

    // 포그라운드 상태 정적 변수 - MyFirebaseMessagingService에서 참조
    public static boolean isInForeground = false;

    // FCM 알림 고정 ID (항상 동일한 ID로 업데이트하여 배지 숫자 정확히 표시)
    public static final int BADGE_NOTIFICATION_ID = 99999;
    // 현재 미읽음 수 (JS에서 업데이트)
    public static int currentUnreadCount = 0;

    // 파일 선택 콜백
    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri dataUri = result.getData().getData();
                if (dataUri != null) results = new Uri[]{dataUri};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // AndroidBridge 등록 (window.Android 로 접근)
        webView.addJavascriptInterface(new AndroidBridge(this), "Android");
        // AndroidBridge 등록 (window.AndroidBridge 로도 접근 가능)
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://eventtv-gpdc5ulb.manus.space") ||
                    url.startsWith("https://3000-")) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                fileChooserLauncher.launch(intent);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // FCM 토큰 갱신
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                android.util.Log.d("EventTV", "[FCM] 토큰: " + task.getResult().substring(0, Math.min(20, task.getResult().length())) + "...");
            }
        });

        createNotificationChannel();

        // 알림 탭으로 진입한 경우 URL 처리
        String url = "https://eventtv-gpdc5ulb.manus.space/messenger";
        if (getIntent() != null && getIntent().getStringExtra("url") != null) {
            url = getIntent().getStringExtra("url");
        }
        webView.loadUrl(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getStringExtra("url") != null) {
            String url = intent.getStringExtra("url");
            webView.loadUrl(url);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        // 앱 포그라운드 복귀 시 JS에 알림
        webView.post(() -> webView.evaluateJavascript(
            "if(window.onAppForeground) window.onAppForeground();", null));
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInForeground = false;
        // 앱 백그라운드 전환 시 JS에 알림
        webView.post(() -> webView.evaluateJavascript(
            "if(window.onAppBackground) window.onAppBackground();", null));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            // v6 채널: 소리 재활성화 - 볼륨은 AudioManager.setStreamVolume()으로 직접 제어
            Uri soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build();

            // ── 진동 없음 채널 (v6) ──────────────────────────────────────────────
            NotificationChannel chVibOff = new NotificationChannel(
                CHANNEL_VIB_OFF, "EventTV 메시지 (진동 없음)", NotificationManager.IMPORTANCE_HIGH);
            chVibOff.setDescription("EventTV 메신저 알림 - 진동 없음");
            chVibOff.setShowBadge(true);
            chVibOff.enableVibration(false);
            chVibOff.enableLights(true);
            chVibOff.setLightColor(0xFF00FF00);
            chVibOff.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibOff);

            // ── 짧게 (300ms) 채널 (v6) ──────────────────────────────────────────
            NotificationChannel chVibShort = new NotificationChannel(
                CHANNEL_VIB_SHORT, "EventTV 메시지 (짧은 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibShort.setDescription("EventTV 메신저 알림 - 짧은 진동 300ms");
            chVibShort.setShowBadge(true);
            chVibShort.enableVibration(true);
            chVibShort.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 300});
            chVibShort.enableLights(true);
            chVibShort.setLightColor(0xFF00FF00);
            chVibShort.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibShort);

            // ── 기본 (700ms) 채널 (v6) ──────────────────────────────────────────
            NotificationChannel chVibDefault = new NotificationChannel(
                CHANNEL_VIB_DEFAULT, "EventTV 메시지 (기본 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibDefault.setDescription("EventTV 메신저 알림 - 기본 진동 700ms");
            chVibDefault.setShowBadge(true);
            chVibDefault.enableVibration(true);
            chVibDefault.setVibrationPattern(new long[]{0, 700, 200, 700, 200, 700});
            chVibDefault.enableLights(true);
            chVibDefault.setLightColor(0xFF00FF00);
            chVibDefault.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibDefault);

            // ── 길게 (1500ms) 채널 (v6) ─────────────────────────────────────────
            NotificationChannel chVibLong = new NotificationChannel(
                CHANNEL_VIB_LONG, "EventTV 메시지 (긴 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibLong.setDescription("EventTV 메신저 알림 - 긴 진동 1500ms");
            chVibLong.setShowBadge(true);
            chVibLong.enableVibration(true);
            chVibLong.setVibrationPattern(new long[]{0, 1500, 300, 1500, 300, 1500});
            chVibLong.enableLights(true);
            chVibLong.setLightColor(0xFF00FF00);
            chVibLong.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibLong);

            // ── 포그라운드 알림 채널 (낮은 우선순위 - 소리/진동 없음) ──────────
            NotificationChannel fgChannel = new NotificationChannel(
                CHANNEL_ID_FOREGROUND, "EventTV 앱 내 알림", NotificationManager.IMPORTANCE_LOW);
            fgChannel.setDescription("앱 사용 중 알림");
            fgChannel.enableVibration(false);
            fgChannel.setSound(null, null);
            manager.createNotificationChannel(fgChannel);

            // ── 기존 채널 (하위 호환용 - 기본 진동과 동일하게 유지) ─────────────
            NotificationChannel legacyChannel = new NotificationChannel(
                CHANNEL_ID, "EventTV 메시지", NotificationManager.IMPORTANCE_HIGH);
            legacyChannel.setDescription("EventTV 메신저 알림");
            legacyChannel.setShowBadge(true);
            legacyChannel.enableVibration(true);
            legacyChannel.setVibrationPattern(new long[]{0, 700, 200, 700, 200, 700});
            legacyChannel.enableLights(true);
            legacyChannel.setLightColor(0xFF00FF00);
            legacyChannel.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(legacyChannel);
        }
    }
}
