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
    // 포그라운드 상태 정적 변수 - MyFirebaseMessagingService에서 참조
    public static boolean isInForeground = false;

    // 파일 선택 콜백
    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                if (data.getClipData() != null) {
                    // 다중 파일 선택
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // 단일 파일 선택
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 알림 채널 생성 (Android 8.0+)
        createNotificationChannel();

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EventTVApp/1.0"
        );

        // JavaScript → Android 브릿지
        webView.addJavascriptInterface(new AndroidBridge(this), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://eventtv-gpdc5ulb.manus.space")) {
                    return false; // WebView 내에서 처리
                }
                // 외부 링크는 브라우저로
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    android.util.Log.e("EventTV", "URL open error: " + e.getMessage());
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 페이지 로드 완료 후 FCM 토큰 재전달 (타이밍 문제 대비)
                FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            String fcmToken = task.getResult();
                            view.post(() ->
                                view.evaluateJavascript(
                                    "window.fcmToken = '" + fcmToken + "'; " +
                                    "window.__fcmToken = '" + fcmToken + "'; " +
                                    "if (window.onFcmToken) window.onFcmToken('" + fcmToken + "');",
                                    null
                                )
                            );
                        }
                    });
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            // ── 파일 선택 창 처리 (Android 5.0+) ──────────────────────────────
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // 이전 콜백이 남아있으면 취소 처리
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                // accept 타입 확인 (이미지 전용 여부)
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean imageOnly = acceptTypes != null && acceptTypes.length == 1
                    && acceptTypes[0].equals("image/*");

                // 파일 선택 Intent 생성
                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                if (imageOnly) {
                    contentIntent.setType("image/*");
                } else {
                    contentIntent.setType("*/*");
                    // 다중 파일 선택 허용
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    // 이미지, 문서, 영상 등 모두 허용
                    String[] mimeTypes = {
                        "image/*", "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/zip", "video/*", "audio/*", "text/plain"
                    };
                    contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                }

                Intent chooserIntent = Intent.createChooser(contentIntent, "파일 선택");
                fileChooserLauncher.launch(chooserIntent);
                return true;
            }
        });

        // FCM 알림으로 앱 실행 시 처리
        handleIntent(getIntent());

        webView.loadUrl("https://eventtv-gpdc5ulb.manus.space/messenger");

        // FCM 토큰 가져오기 (webView 초기화 후에 실행)
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) {
                    String token = task.getResult();
                    android.util.Log.d("EventTV", "[FCM] 토큰 획득: " + token.substring(0, Math.min(20, token.length())) + "...");
                    webView.post(() ->
                        webView.evaluateJavascript(
                            "window.fcmToken = '" + token + "'; " +
                            "window.__fcmToken = '" + token + "'; " +
                            "if (window.onFcmToken) window.onFcmToken('" + token + "');",
                            null
                        )
                    );
                } else {
                    android.util.Log.w("EventTV", "[FCM] 토큰 획득 실패: " + task.getException());
                }
            });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("url")) {
            String url = intent.getStringExtra("url");
            if (webView != null && url != null) {
                if (!url.startsWith("http")) {
                    url = "https://eventtv-gpdc5ulb.manus.space" + (url.startsWith("/") ? url : "/" + url);
                }
                webView.loadUrl(url);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 정적 변수로 포그라운드 상태 설정 (MyFirebaseMessagingService에서 참조)
        isInForeground = true;
        // 앱 포그라운드 진입 시 모든 알림 취소 + 배지 초기화
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();  // 모든 알림 제거
        } catch (Exception ignored) {}
        try {
            ShortcutBadger.removeCount(getApplicationContext());
        } catch (Exception ignored) {}
        // 소켓으로도 서버에 알림
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "window._androidIsInForeground = true; " +
                    "if (window._messengerSocket && window._messengerSocket.connected) { " +
                    "  window._messengerSocket.emit('app_foreground'); " +
                    "}",
                    null
                )
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 정적 변수로 백그라운드 상태 설정
        isInForeground = false;
        // 소켓으로도 서버에 알림
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "window._androidIsInForeground = false; " +
                    "if (window._messengerSocket && window._messengerSocket.connected) { " +
                    "  window._messengerSocket.emit('app_background'); " +
                    "}",
                    null
                )
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            // JS에 androidBack 커스텀 이벤트를 먼저 전달
            webView.evaluateJavascript(
                "(function() {" +
                "  var e = new CustomEvent('androidBack', { cancelable: true });" +
                "  var handled = !window.dispatchEvent(e);" +
                "  return handled ? 'handled' : 'nothandled';" +
                "})()",
                value -> {
                    if (value == null || !value.contains("handled")) {
                        runOnUiThread(() -> {
                            if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                MainActivity.super.onBackPressed();
                            }
                        });
                    }
                }
            );
        } else {
            super.onBackPressed();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 메인 알림 채널 (높은 우선순위 - 소리 + 진동)
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "EventTV 메시지",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("EventTV 메신저 알림");
            channel.setShowBadge(true);  // 배지 표시 활성화
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 700, 200, 700, 200, 700, 200, 700, 200, 700, 200, 700});
            channel.enableLights(true);
            channel.setLightColor(0xFF00FF00);
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
            channel.setSound(soundUri, audioAttributes);

            // 포그라운드 알림 채널 (낮은 우선순위 - 소리/진동 없음)
            NotificationChannel fgChannel = new NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "EventTV 앱 내 알림",
                NotificationManager.IMPORTANCE_LOW
            );
            fgChannel.setDescription("앱 사용 중 알림");
            fgChannel.enableVibration(false);
            fgChannel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                manager.createNotificationChannel(fgChannel);
            }
        }
    }
}
