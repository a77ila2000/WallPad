package com.homepad.companion;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The existing CCTV portal supplies HTTPS video and the merged motion timeline. */
final class PersonalCctvView extends WebView {
    static final int REQUEST_MICROPHONE = 1004;
    static final String ORIGIN = BuildConfig.CCTV_ORIGIN;
    private final ExecutorService authentication = Executors.newSingleThreadExecutor();
    private String camera = "";
    private String authenticatedToken = "";
    private String requestedToken = "";
    private long authenticatedUntil;
    private int generation;
    private boolean active;
    private boolean released;
    private int loadFailures;
    private PermissionRequest microphoneRequest;
    private int microphoneGeneration;
    private float touchX, touchY;

    @SuppressLint("SetJavaScriptEnabled")
    PersonalCctvView(Context context) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        setContentDescription("우리집 실시간 CCTV와 모션 녹화 영상");
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setTextZoom(Math.round(100 * getResources().getConfiguration().fontScale));
        setVerticalScrollBarEnabled(false);
        // This bridge only resizes our view; it cannot read data or control devices.
        addJavascriptInterface(new ContentLayout(), "HomePadLayout");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false);
        setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                boolean audio = false;
                for (String resource : request.getResources())
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                String page = getUrl();
                if (!audio || !active || released || !trusted(request.getOrigin())
                        || page == null || !"/app".equals(Uri.parse(page).getPath())) {
                    request.deny();
                    return;
                }
                cancelMicrophoneRequest();
                microphoneRequest = request;
                microphoneGeneration = generation;
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    finishMicrophoneRequest();
                else ((Activity) context).requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (microphoneRequest == request) microphoneRequest = null;
            }
        });
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !trusted(request.getUrl());
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (trusted(uri) || "about".equals(uri.getScheme()) || "data".equals(uri.getScheme())
                        || "blob".equals(uri.getScheme())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null,
                        new ByteArrayInputStream(new byte[0]));
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (active && trusted(Uri.parse(url)) && "/login".equals(Uri.parse(url).getPath())) {
                    recoverLoad("카메라 연결 인증을 갱신하고 있습니다.", true);
                    return;
                }
                if (!active || !trusted(Uri.parse(url)) || !"/app".equals(Uri.parse(url).getPath())) return;
                loadFailures = 0;
                // The list arrives after page load and the recorded player can
                // appear later. Observe intrinsic body height, not viewport height.
                evaluateJavascript("(()=>{window.homePadResize?.disconnect();"
                        + "const resize=()=>HomePadLayout.setContentHeight("
                        + "Math.ceil(document.body.getBoundingClientRect().height)," + generation + ");"
                        + "window.homePadResize=new ResizeObserver(resize);"
                        + "window.homePadResize.observe(document.body);resize()})()", null);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) recoverLoad("카메라 서버 연결이 잠시 끊겼습니다.", false);
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) recoverLoad("카메라 서버 연결을 다시 확인합니다.",
                        response.getStatusCode() == 401 || response.getStatusCode() == 403);
            }
        });
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
    }

    private final class ContentLayout {
        @JavascriptInterface public void setContentHeight(int cssHeight, int request) {
            if (cssHeight < 1 || cssHeight > 8192) return;
            post(() -> {
                if (!active || released || generation != request) return;
                int pixels = Math.round(cssHeight * getResources().getDisplayMetrics().density);
                ViewGroup.LayoutParams params = getLayoutParams();
                if (params != null && params.height != pixels) {
                    params.height = pixels;
                    setLayoutParams(params);
                }
            });
        }
    }

    // WebView handles DOM clicks and accessibility; this only routes scroll gestures.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchX = event.getX();
            touchY = event.getY();
        }
        float dx = Math.abs(event.getX() - touchX), dy = Math.abs(event.getY() - touchY);
        boolean horizontal = event.getActionMasked() == MotionEvent.ACTION_MOVE
                && dx > ViewConfiguration.get(getContext()).getScaledTouchSlop() && dx > dy;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(horizontal);
        return handled;
    }

    private static boolean trusted(Uri uri) {
        return "https".equals(uri.getScheme())
                && Uri.parse(ORIGIN).getHost() != null
                && Uri.parse(ORIGIN).getHost().equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    void finishMicrophoneRequest() {
        PermissionRequest request = microphoneRequest;
        microphoneRequest = null;
        if (request == null) return;
        if (active && !released && generation == microphoneGeneration
                && getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else request.deny();
    }

    private void cancelMicrophoneRequest() {
        if (microphoneRequest == null) return;
        microphoneRequest.deny();
        microphoneRequest = null;
    }

    private String pageUrl(String cameraId) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return ORIGIN + "/app?camera=" + cameraId + "&theme=" + (dark ? "dark" : "light");
    }

    void showCamera(String requestedCamera, String token) {
        showCamera(requestedCamera, token, true);
    }

    private void showCamera(String requestedCamera, String token, boolean newSelection) {
        if (released || (active && camera.equals(requestedCamera) && authenticatedToken.equals(token))) return;
        if (newSelection) loadFailures = 0;
        active = true;
        camera = requestedCamera;
        requestedToken = token;
        int request = ++generation;
        onResume();
        if (authenticatedToken.equals(token) && SystemClock.elapsedRealtime() < authenticatedUntil) {
            loadUrl(pageUrl(requestedCamera));
            return;
        }
        showMessage("카메라와 녹화 목록 연결 중…");
        authentication.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("homepad", true);
                JSONObject session = new ApiClient(ORIGIN, token).postJson("/login", body);
                String cookie = session.getString("cookie");
                post(() -> {
                    if (released || !active || generation != request) return;
                    CookieManager.getInstance().setCookie(ORIGIN, cookie, accepted -> {
                        if (released || !active || generation != request) return;
                        if (!Boolean.TRUE.equals(accepted)) {
                            recoverLoad("카메라 연결 인증을 저장하지 못했습니다.", true);
                            return;
                        }
                        authenticatedToken = token;
                        authenticatedUntil = SystemClock.elapsedRealtime()
                                + Math.max(0, session.optInt("expiresIn", 0) - 60) * 1000L;
                        loadUrl(pageUrl(requestedCamera));
                    });
                });
            } catch (Exception error) {
                post(() -> {
                    if (released || !active || generation != request) return;
                    recoverLoad("카메라 연결 실패: " + error.getMessage(), true);
                });
            }
        });
    }

    private void recoverLoad(String message, boolean renewAuthentication) {
        if (!active || released) return;
        int request = generation;
        active = false;
        if (renewAuthentication) authenticatedUntil = 0;
        int attempt = ++loadFailures;
        showMessage(message + (attempt <= 3 ? " 자동으로 재연결합니다…" : " 카메라를 다시 선택해 주세요."));
        if (attempt <= 3) postDelayed(() -> {
            if (!released && !active && request == generation)
                showCamera(camera, requestedToken, false);
        }, attempt * 2000L);
    }

    private void showMessage(String message) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        loadDataWithBaseURL(ORIGIN, "<html lang='ko'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>:root{color-scheme:" + (dark ? "dark" : "light")
                + "}body{font:16px sans-serif;padding:12px}</style>"
                + "<p role='status'>" + TextUtils.htmlEncode(message) + "</p></html>", "text/html", "UTF-8", null);
    }

    void suspendVideo() {
        cancelMicrophoneRequest();
        active = false;
        generation++;
        stopLoading();
        loadUrl("about:blank");
        onPause();
    }

    @Override public void destroy() {
        cancelMicrophoneRequest();
        released = true;
        generation++;
        authentication.shutdownNow();
        removeJavascriptInterface("HomePadLayout");
        super.destroy();
    }
}
