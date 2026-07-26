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

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    public static final String CHANNEL_ID = "eventtv_messages_v2"; // v2: 소리+진동 설정 추가

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
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 페이지 로드 완료 후 저장된 FCM 토큰이 있으면 재전달
                // (FCM 토큰이 페이지 로드보다 먼저 도착한 경우 대비)
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

        // FCM 토큰 가져오기 (webView 초기화 후에 실행해야 webView가 null이 아님)
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    String token = task.getResult();
                    android.util.Log.d("EventTV", "[FCM] 토큰 획득: " + token.substring(0, Math.min(20, token.length())) + "...");
                    // 토큰을 WebView의 JavaScript로 전달
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
                webView.loadUrl(url);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 앱이 포그라운드로 돌아올 때 소켓으로 서버에 알림 → FCM 발송 차단
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "(function() {" +
                    "  window._androidIsInForeground = true;" +
                    "  var s = window._messengerSocket;" +
                    "  if (s && s.connected) { s.emit('app_foreground'); console.log('[Android] app_foreground 전송'); }" +
                    "})()",
                    null
                )
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 앱이 백그라운드로 갈 때 소켓으로 서버에 알림 → FCM 발송 허용
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "(function() {" +
                    "  window._androidIsInForeground = false;" +
                    "  var s = window._messengerSocket;" +
                    "  if (s && s.connected) { s.emit('app_background'); console.log('[Android] app_background 전송'); }" +
                    "})()",
                    null
                )
            );
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            // JS에 androidBack 커스텀 이벤트를 먼저 전달
            // 이벤트를 JS에서 처리했는지 여부를 콜백으로 받음
            webView.evaluateJavascript(
                "(function() {" +
                "  var e = new CustomEvent('androidBack', { cancelable: true });" +
                "  var handled = !window.dispatchEvent(e);" +
                "  return handled ? 'handled' : 'nothandled';" +
                "})()",
                value -> {
                    if (value == null || !value.contains("handled")) {
                        // JS에서 처리하지 않은 경우
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
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "EventTV 메시지",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("EventTV 메신저 알림");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 100, 300});
            // 알림 소리 설정
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
            channel.setSound(soundUri, audioAttributes);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
