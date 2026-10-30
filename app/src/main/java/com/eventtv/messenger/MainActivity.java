package com.eventtv.messenger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import com.google.firebase.messaging.FirebaseMessaging;
import me.leolin.shortcutbadger.ShortcutBadger;
import org.json.JSONObject;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    // 새 설치본은 이 버전 매개변수로 최신 메신저 HTML·JS를 다시 요청한다.
    private static final String MESSENGER_URL =
        "https://eventtv-gpdc5ulb.manus.space/messenger?androidVersion=1.4.41";
    private WebView webView;
    private boolean isHandlingSystemBack = false;
    private OnBackPressedCallback systemBackCallback;
    public static final String CHANNEL_ID = "eventtv_messages_v5";
    public static final String CHANNEL_ID_FOREGROUND = "eventtv_messages_fg";
    // 홈 화면 배지는 이 단일 채널의 고정 알림만 사용한다.
    public static final String CHANNEL_BADGE = "eventtv_badge_v1";

    // Android는 생성 뒤 채널의 소리·중요도를 앱에서 변경할 수 없다.
    // v4 채널은 과거 설치본의 무음/낮은 중요도 설정과 분리해 새 기본값을 적용한다.
    public static final String CHANNEL_VIB_OFF     = "eventtv_vib_off_v4";
    public static final String CHANNEL_VIB_SHORT   = "eventtv_vib_short_v4";
    public static final String CHANNEL_VIB_DEFAULT = "eventtv_vib_default_v4";
    public static final String CHANNEL_VIB_LONG    = "eventtv_vib_long_v4";
    public static final String CHANNEL_SILENT_VIB_OFF     = "eventtv_silent_vib_off_v4";
    public static final String CHANNEL_SILENT_VIB_SHORT   = "eventtv_silent_vib_short_v4";
    public static final String CHANNEL_SILENT_VIB_DEFAULT = "eventtv_silent_vib_default_v4";
    public static final String CHANNEL_SILENT_VIB_LONG    = "eventtv_silent_vib_long_v4";
    private static final String KEY_NOTIFICATION_PERMISSION_REQUESTED =
        "notification_permission_requested";

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
    // 다른 앱에서 공유한 텍스트·URL은 사용자가 채팅방을 연 뒤 메시지 입력창에 채운다.
    private String pendingSharedText = "";
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

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            android.util.Log.i("EventTV", "[알림] Android 알림 권한 결과: " + granted);
            notifyNotificationPermissionStatus();
        });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 알림 채널 생성 (Android 8.0+)
        ensureNotificationChannels(this);
        // Android 13 이상에서는 첫 실행 때 시스템 알림 권한을 요청한다. 거부 후에는
        // 사용자가 메신저 설정에서 다시 선택할 때만 앱 설정 화면을 열어 반복 팝업을 막는다.
        requestNotificationPermissionIfNeeded(false);

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
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EventTVApp/1.4.41"
        );

        // JavaScript → Android 브릿지
        webView.addJavascriptInterface(new AndroidBridge(this), "Android");

        // Android 13 이상 예측 뒤로가기와 하단 우측 시스템 키를 동일한 콜백으로 처리한다.
        systemBackCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                dispatchMessengerBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, systemBackCallback);

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
                // 최초 공유 진입 시 handleIntent의 이벤트가 페이지 로드 전에 발생할 수 있다.
                // 페이지가 준비된 시점에 한 번 더 알리면 채팅방 선택 뒤에도 공유 내용이 유지된다.
                if (!pendingSharedUris.isEmpty()) notifySharedFilesAvailable();
                if (!pendingSharedText.isEmpty()) notifySharedTextAvailable();
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
                notifyNotificationPermissionStatus();
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
        if (receiveSharedContent(intent)) {
            // ACTION_SEND 인텐트를 그대로 Activity에 남겨두면 다음 날 앱을 다시 열거나
            // 화면 회전으로 Activity가 재생성될 때 같은 파일을 다시 공유 대기열에 넣는다.
            // 파일은 아래 pendingSharedUris에만 한 번 보관하고, 원본 인텐트는 즉시 폐기한다.
            clearHandledShareIntent();
            if (webView != null) {
                String currentUrl = webView.getUrl();
                if (currentUrl == null || !currentUrl.contains("/messenger")) {
                    webView.loadUrl(MESSENGER_URL);
                } else {
                    notifySharedFilesAvailable();
                    notifySharedTextAvailable();
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

    /**
     * 이미 처리한 Android 공유 인텐트가 Activity 재생성 시 다시 읽히지 않게 한다.
     * 대기 파일 자체는 pendingSharedUris에 유지되므로 사용자가 채팅방을 선택한 뒤
     * 한 번만 첨부할 수 있다.
     */
    private void clearHandledShareIntent() {
        Intent consumedIntent = new Intent(Intent.ACTION_MAIN);
        consumedIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        setIntent(consumedIntent);
    }

    @SuppressWarnings("deprecation")
    private boolean receiveSharedContent(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return false;
        }

        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText == null || sharedText.trim().isEmpty()) {
            sharedText = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
        }
        if (sharedText != null && !sharedText.trim().isEmpty()) {
            pendingSharedText = sharedText.trim();
        }

        boolean hasSharedFiles = receiveSharedFiles(intent);
        return hasSharedFiles || !pendingSharedText.isEmpty();
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

    /**
     * 다른 앱에서 공유한 텍스트·URL을 WebView가 한 번만 가져가도록 반환한다.
     */
    public String consumePendingSharedText() {
        String sharedText = pendingSharedText;
        pendingSharedText = "";
        return sharedText;
    }

    private void notifySharedFilesAvailable() {
        if (webView == null) return;
        final int count = pendingSharedUris.size();
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('eventtvSharedFilesAvailable', { detail: { count: " + count + " } }));",
            null
        ));
    }

    private void notifySharedTextAvailable() {
        if (webView == null || pendingSharedText.isEmpty()) return;
        final String sharedText = JSONObject.quote(pendingSharedText);
        webView.post(() -> webView.evaluateJavascript(
            "window.__eventtvPendingSharedText = " + sharedText + ";" +
            "window.dispatchEvent(new CustomEvent('eventtvSharedTextAvailable'));",
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
        notifyNotificationPermissionStatus();
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
                    // WebView가 백그라운드 동안 보존한 끊긴 소켓은 connected 값이 늦게
                    // 갱신될 수 있다. 먼저 웹 메신저에 재연결을 요청하고, 열린 대화의
                    // 최신 메시지 조회도 즉시 시작한다.
                    "  if (typeof window.messengerReconnectWs === 'function') window.messengerReconnectWs(); " +
                    "  window.dispatchEvent(new Event('eventtvMessengerForeground')); " +
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
        // 서버에만 백그라운드 상태를 알린다. 소켓을 강제로 끊으면 복귀 시 WebSocket
        // handshake의 최대 대기 시간만큼 새 메시지 표시가 늦어질 수 있으므로, 실제
        // 네트워크 단절이 발생했을 때만 웹 클라이언트가 재연결하도록 유지한다.
        if (webView != null) {
            webView.evaluateJavascript(
                "window._androidIsInForeground = false; " +
                "if (window._messengerSocket && window._messengerSocket.connected) { " +
                "  window._messengerSocket.emit('app_background'); " +
                "}",
                null
            );
            webView.onPause();
        }
        // 백그라운드 전환 시 별도 배지 알림 발행 안 함
        // FCM 알림의 setNumber(badgeCount) + 고정 ID(BADGE_NOTIFICATION_ID)가 배지 역할 담당
        // 사일런트 알림 방식은 FCM 알림과 충돌하여 배지가 나타났다 사라지는 문제 발생
    }

    private void dispatchMessengerBack() {
        if (isHandlingSystemBack) return;
        if (webView == null) {
            finishFromSystemBack();
            return;
        }
        isHandlingSystemBack = true;
        // JS에 androidBack 커스텀 이벤트를 먼저 전달해 열린 모바일 채팅방은 목록으로 복귀한다.
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
                isHandlingSystemBack = false;
                if (!wasHandled) {
                    runOnUiThread(() -> {
                        if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finishFromSystemBack();
                        }
                    });
                }
            }
        );
    }

    private void finishFromSystemBack() {
        if (systemBackCallback != null) systemBackCallback.setEnabled(false);
        getOnBackPressedDispatcher().onBackPressed();
    }

    /**
     * Android 13+의 런타임 알림 권한과 사용자가 앱 전체 알림을 끈 상태를 함께 확인한다.
     * WebView는 이 값을 읽어 "켜짐"으로 오인하지 않고, 사용자 클릭 때만 설정으로 안내한다.
     */
    public String getNotificationPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "permission_required";
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            return "disabled";
        }
        return "granted";
    }

    /** 메시지 알림에 사용할 앱 전체·선택 채널 상태를 함께 확인한다. channelId가 null이면 앱 전체만 확인한다. */
    public static boolean canDeliverMessageNotification(Context context, String channelId) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channelId != null) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(channelId);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    /** WebView의 사용자 동작에서 권한을 요청하거나, 이미 거부된 경우 앱 알림 설정을 연다. */
    public boolean requestNotificationPermissionFromUser() {
        return requestNotificationPermissionIfNeeded(true);
    }

    private boolean requestNotificationPermissionIfNeeded(boolean userInitiated) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (!enabled && userInitiated) openAppNotificationSettings();
            return enabled;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            if (!enabled && userInitiated) openAppNotificationSettings();
            return enabled;
        }

        SharedPreferences prefs = getSharedPreferences(AndroidBridge.PREFS_NAME, MODE_PRIVATE);
        boolean alreadyRequested = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false);
        if (!alreadyRequested) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply();
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (userInitiated) {
            openAppNotificationSettings();
        }
        return false;
    }

    private void openAppNotificationSettings() {
        Intent settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(settingsIntent);
    }

    /** 네이티브 권한·설정 변경을 열린 WebView의 알림 메뉴에 즉시 반영한다. */
    private void notifyNotificationPermissionStatus() {
        if (webView == null) return;
        final String status = JSONObject.quote(getNotificationPermissionStatus());
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('eventtvNotificationPermissionChanged', { detail: { status: " + status + " } }));",
            null
        ));
    }

    /** FCM 서비스가 Activity보다 먼저 시작되어도 최신 채널을 보장한다. */
    public static void ensureNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build();

        long[] shortPattern = new long[]{0, 300, 100, 300, 100, 300};
        long[] defaultPattern = new long[]{0, 700, 200, 700, 200, 700};
        long[] longPattern = new long[]{0, 1500, 300, 1500, 300, 1500};

        createMessageChannel(manager, CHANNEL_VIB_OFF, "EventTV 메시지 (소리 · 진동 없음)", null, true, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_VIB_SHORT, "EventTV 메시지 (소리 · 짧은 진동)", shortPattern, true, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_VIB_DEFAULT, "EventTV 메시지 (소리 · 기본 진동)", defaultPattern, true, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_VIB_LONG, "EventTV 메시지 (소리 · 긴 진동)", longPattern, true, soundUri, audioAttributes);

        createMessageChannel(manager, CHANNEL_SILENT_VIB_OFF, "EventTV 메시지 (무음 · 진동 없음)", null, false, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_SILENT_VIB_SHORT, "EventTV 메시지 (무음 · 짧은 진동)", shortPattern, false, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_SILENT_VIB_DEFAULT, "EventTV 메시지 (무음 · 기본 진동)", defaultPattern, false, soundUri, audioAttributes);
        createMessageChannel(manager, CHANNEL_SILENT_VIB_LONG, "EventTV 메시지 (무음 · 긴 진동)", longPattern, false, soundUri, audioAttributes);

        NotificationChannel fgChannel = new NotificationChannel(
            CHANNEL_ID_FOREGROUND, "EventTV 앱 내 알림", NotificationManager.IMPORTANCE_LOW);
        fgChannel.setDescription("앱 사용 중 알림");
        fgChannel.enableVibration(false);
        fgChannel.setSound(null, null);
        manager.createNotificationChannel(fgChannel);

        // Android 8.0 미만과 이전 앱 경로의 하위 호환용 채널
        NotificationChannel legacyChannel = new NotificationChannel(
            CHANNEL_ID, "EventTV 메시지", NotificationManager.IMPORTANCE_HIGH);
        legacyChannel.setDescription("EventTV 메신저 알림");
        legacyChannel.setShowBadge(false);
        legacyChannel.enableVibration(true);
        legacyChannel.setVibrationPattern(defaultPattern);
        legacyChannel.setSound(soundUri, audioAttributes);
        manager.createNotificationChannel(legacyChannel);

        NotificationChannel badgeChannel = new NotificationChannel(
            CHANNEL_BADGE, "EventTV 안읽은 메시지 수", NotificationManager.IMPORTANCE_LOW);
        badgeChannel.setDescription("홈 화면의 EventTV 미읽음 메시지 배지 동기화용 알림");
        badgeChannel.setShowBadge(true);
        badgeChannel.enableVibration(false);
        badgeChannel.setSound(null, null);
        manager.createNotificationChannel(badgeChannel);
    }

    private static void createMessageChannel(
        NotificationManager manager,
        String channelId,
        String name,
        long[] vibrationPattern,
        boolean soundEnabled,
        Uri soundUri,
        AudioAttributes audioAttributes
    ) {
        NotificationChannel channel = new NotificationChannel(
            channelId, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("EventTV 메신저 메시지 알림");
        channel.setShowBadge(false);
        channel.enableLights(true);
        channel.setLightColor(0xFF00FF00);
        channel.enableVibration(vibrationPattern != null);
        if (vibrationPattern != null) channel.setVibrationPattern(vibrationPattern);
        channel.setSound(soundEnabled ? soundUri : null, soundEnabled ? audioAttributes : null);
        manager.createNotificationChannel(channel);
    }

    /** 사용자가 고른 소리/진동 설정에 대응하는 채널을 반환한다. */
    public static String getMessageChannelId(boolean soundEnabled, int vibrationDurationMs) {
        if (vibrationDurationMs <= 0) {
            return soundEnabled ? CHANNEL_VIB_OFF : CHANNEL_SILENT_VIB_OFF;
        }
        if (vibrationDurationMs <= 300) {
            return soundEnabled ? CHANNEL_VIB_SHORT : CHANNEL_SILENT_VIB_SHORT;
        }
        if (vibrationDurationMs <= 700) {
            return soundEnabled ? CHANNEL_VIB_DEFAULT : CHANNEL_SILENT_VIB_DEFAULT;
        }
        return soundEnabled ? CHANNEL_VIB_LONG : CHANNEL_SILENT_VIB_LONG;
    }

}
