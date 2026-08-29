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
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    // 새 설치본은 이 버전 매개변수로 최신 메신저 HTML·JS를 다시 요청한다.
    private static final String MESSENGER_URL =
        "https://eventtv-gpdc5ulb.manus.space/messenger?androidVersion=1.4.33";
    private WebView webView;
    public static final String CHANNEL_ID = "eventtv_messages_v5";
    public static final String CHANNEL_ID_FOREGROUND = "eventtv_messages_fg";
    // 홈 화면 배지는 이 단일 채널의 고정 알림만 사용한다.
    public static final String CHANNEL_BADGE = "eventtv_badge_v1";

    // 진동별 채널 ID (Android 8.0+ 진동 패턴 제어용)
    // v3: 팝업 알림이 배지 수에 합산되지 않도록 전용 채널로 교체
    public static final String CHANNEL_VIB_OFF     = "eventtv_vib_off_v3";     // 진동 없음
    public static final String CHANNEL_VIB_SHORT   = "eventtv_vib_short_v3";   // 짧게 300ms
    public static final String CHANNEL_VIB_DEFAULT = "eventtv_vib_default_v3"; // 기본 700ms
    public static final String CHANNEL_VIB_LONG    = "eventtv_vib_long_v3";    // 길게 1500ms

    // 포그라운드 상태 정적 변수 - MyFirebaseMessagingService에서 참조
    public static boolean isInForeground = false;

    // FCM 알림 고정 ID (항상 동일한 ID로 업데이트하여 배지 숫자 정확히 표시)
    public static final int BADGE_NOTIFICATION_ID = 99999;
    // 현재 미읽음 수 (JS에서 업데이트)
    public static int currentUnreadCount = 0;

    // 파일 선택 콜백
    private ValueCallback<Uri[]> filePathCallback;
    // 공유 메뉴에서 받은 파일은 사용자가 대화방을 고른 뒤 첨부할 때 WebView에 전달한다.
    private final ArrayList<Uri> pendingSharedUris = new ArrayList<>();
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
        // DOM 저장소와 함께 WebView 데이터베이스를 사용해 재실행 시 세션·캐시 복원을 돕는다.
        // 실시간 메신저 특성상 LOAD_DEFAULT는 유지하여 오래된 대화만 표시하지 않는다.
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EventTVApp/1.4.33"
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

                // 공유 메뉴에서 전달된 파일은 메신저 첨부 버튼을 누르면 바로 반환한다.
                if (!pendingSharedUris.isEmpty()) {
                    Uri[] sharedUris = pendingSharedUris.toArray(new Uri[0]);
                    pendingSharedUris.clear();
                    filePathCallback.onReceiveValue(sharedUris);
                    MainActivity.this.filePathCallback = null;
                    notifySharedFilesAvailable();
                    return true;
                }

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

        webView.loadUrl(MESSENGER_URL);

        // FCM 알림 또는 Android 공유 메뉴로 앱을 실행한 경우 처리
        handleIntent(getIntent());

        // FCM 토큰은 onPageFinished에서 한 번만 주입한다. 초기 loadUrl 직후의 중복
        // 요청은 아직 준비되지 않은 JavaScript 컨텍스트에 전달돼 시작 속도만 늦췄다.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (receiveSharedFiles(intent)) {
            if (webView != null) {
                String currentUrl = webView.getUrl();
                if (currentUrl == null || !currentUrl.contains("/messenger")) {
                    webView.loadUrl(MESSENGER_URL);
                } else {
                    notifySharedFilesAvailable();
                }
            }
            return;
        }
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

    @SuppressWarnings("deprecation")
    private boolean receiveSharedFiles(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return false;
        }

        ArrayList<Uri> receivedUris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action) && intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) receivedUris.add(uri);
            }
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) receivedUris.add(uri);
            if (intent.getClipData() != null) {
                for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                    Uri clipUri = intent.getClipData().getItemAt(i).getUri();
                    if (clipUri != null && !receivedUris.contains(clipUri)) receivedUris.add(clipUri);
                }
            }
        }

        if (receivedUris.isEmpty()) return false;
        pendingSharedUris.clear();
        pendingSharedUris.addAll(receivedUris);
        notifySharedFilesAvailable();
        return true;
    }

    public int getPendingSharedFileCount() {
        return pendingSharedUris.size();
    }

    private void notifySharedFilesAvailable() {
        if (webView == null) return;
        final int count = pendingSharedUris.size();
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('eventtvSharedFilesAvailable', { detail: { count: " + count + " } }));",
            null
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 정적 변수로 포그라운드 상태 설정 (MyFirebaseMessagingService에서 참조)
        isInForeground = true;
        // 백그라운드에서 중지된 WebView 타이머가 있을 경우 즉시 재개한다.
        if (webView != null) webView.onResume();
        // 앱 포그라운드 진입 시 알림 트레이의 FCM 알림 모두 취소 → 아이콘 배지 제거
        // (알림 트레이에 setNumber가 있으면 ShortcutBadger보다 우선 표시되므로 반드시 취소)
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancelAll();
        } catch (Exception ignored) {}
        // ShortcutBadger도 0으로 초기화
        try {
            me.leolin.shortcutbadger.ShortcutBadger.removeCount(this);
        } catch (Exception ignored) {}
        // 소켓으로 포그라운드 상태 서버에 알림 (badge_sync 이벤트 수신용)
        // 현재 열린 채널 정보 포함 → 서버가 먼저 읽음 처리 후 badge_sync 발송 (race condition 방지)
        if (webView != null) {
            webView.post(() ->
                webView.evaluateJavascript(
                    "(function() { " +
                    "  window._androidIsInForeground = true; " +
                    "  if (!window._messengerSocket || !window._messengerSocket.connected) return; " +
                    "  var _chId = window._activeChannelId || null; " +
                    "  var _lastMsgId = null; " +
                    "  if (_chId && window._trpcUtils) { " +
                    "    try { " +
                    "      var _chs = window._trpcUtils.messenger.getMyChannels.getData(window._trpcTokenArg); " +
                    "      if (_chs) { " +
                    "        var _ch = _chs.find(function(c) { return c.id === _chId; }); " +
                    "        if (_ch && _ch.lastMessage) _lastMsgId = _ch.lastMessage.id; " +
                    "      } " +
                    "    } catch(e) {} " +
                    "  } " +
                    "  var _payload = (_chId && _lastMsgId) ? { currentChannelId: _chId, lastMessageId: _lastMsgId } : undefined; " +
                    "  window._messengerSocket.emit('app_foreground', _payload); " +
                    "})()",
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
        if (webView != null) webView.onPause();
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
        // 백그라운드 전환 시 별도 배지 알림 발행 안 함
        // FCM 알림의 setNumber(badgeCount) + 고정 ID(BADGE_NOTIFICATION_ID)가 배지 역할 담당
        // 사일런트 알림 방식은 FCM 알림과 충돌하여 배지가 나타났다 사라지는 문제 발생
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
                    // evaluateJavascript의 반환값은 JSON 문자열이므로 "nothandled"에도
                    // "handled"가 포함된다. 부분 문자열이 아닌 정확한 결과만 처리 완료로 본다.
                    boolean wasHandled = "\"handled\"".equals(value) || "handled".equals(value);
                    if (!wasHandled) {
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
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();

            // ── 진동 없음 채널 ──────────────────────────────────────────────────
            NotificationChannel chVibOff = new NotificationChannel(
                CHANNEL_VIB_OFF, "EventTV 메시지 (진동 없음)", NotificationManager.IMPORTANCE_HIGH);
            chVibOff.setDescription("EventTV 메신저 알림 - 진동 없음");
            chVibOff.setShowBadge(false);
            chVibOff.enableVibration(false);
            chVibOff.enableLights(true);
            chVibOff.setLightColor(0xFF00FF00);
            chVibOff.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibOff);

            // ── 짧게 (300ms) 채널 ──────────────────────────────────────────────
            NotificationChannel chVibShort = new NotificationChannel(
                CHANNEL_VIB_SHORT, "EventTV 메시지 (짧은 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibShort.setDescription("EventTV 메신저 알림 - 짧은 진동 300ms");
            chVibShort.setShowBadge(false);
            chVibShort.enableVibration(true);
            chVibShort.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 300});
            chVibShort.enableLights(true);
            chVibShort.setLightColor(0xFF00FF00);
            chVibShort.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibShort);

            // ── 기본 (700ms) 채널 ──────────────────────────────────────────────
            NotificationChannel chVibDefault = new NotificationChannel(
                CHANNEL_VIB_DEFAULT, "EventTV 메시지 (기본 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibDefault.setDescription("EventTV 메신저 알림 - 기본 진동 700ms");
            chVibDefault.setShowBadge(false);
            chVibDefault.enableVibration(true);
            chVibDefault.setVibrationPattern(new long[]{0, 700, 200, 700, 200, 700});
            chVibDefault.enableLights(true);
            chVibDefault.setLightColor(0xFF00FF00);
            chVibDefault.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(chVibDefault);

            // ── 길게 (1500ms) 채널 ─────────────────────────────────────────────
            NotificationChannel chVibLong = new NotificationChannel(
                CHANNEL_VIB_LONG, "EventTV 메시지 (긴 진동)", NotificationManager.IMPORTANCE_HIGH);
            chVibLong.setDescription("EventTV 메신저 알림 - 긴 진동 1500ms");
            chVibLong.setShowBadge(false);
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
            legacyChannel.setShowBadge(false);
            legacyChannel.enableVibration(true);
            legacyChannel.setVibrationPattern(new long[]{0, 700, 200, 700, 200, 700});
            legacyChannel.enableLights(true);
            legacyChannel.setLightColor(0xFF00FF00);
            legacyChannel.setSound(soundUri, audioAttributes);
            manager.createNotificationChannel(legacyChannel);

            // 배지 전용 채널: 실제 미읽음 총수만 단일 고정 알림으로 표시
            NotificationChannel badgeChannel = new NotificationChannel(
                CHANNEL_BADGE, "EventTV 안읽은 메시지 수", NotificationManager.IMPORTANCE_LOW);
            badgeChannel.setDescription("홈 화면의 EventTV 미읽음 메시지 배지 동기화용 알림");
            badgeChannel.setShowBadge(true);
            badgeChannel.enableVibration(false);
            badgeChannel.setSound(null, null);
            manager.createNotificationChannel(badgeChannel);
        }
    }
}
