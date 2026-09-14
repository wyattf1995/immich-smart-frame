package com.wyattfleming.frametv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicitly opened dashboard. No renderer is created by the launcher itself. */
public final class DashboardActivity extends Activity {
    private Feature feature;
    private String address;
    private FrameLayout surface;
    private WebView web;
    private TextView status;
    private LinearLayout controls;
    private LinearLayout bar;
    private boolean menuRequested;
    private boolean active;
    private boolean loadFailed;
    private HomeAssistantForecastCapture forecastCapture;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        try { feature = Feature.valueOf(getIntent().getStringExtra("feature")); }
        catch (IllegalArgumentException | NullPointerException error) { finish(); return; }
        address = new TvPreferences(this).url(feature);
        if (feature == Feature.HOME || feature == Feature.WEATHER) forecastCapture = new HomeAssistantForecastCapture(this);
        if (address.isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        surface = new FrameLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(TvUi.BG);
        bar = new LinearLayout(this);
        bar.setVisibility(View.GONE);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 6), TvUi.dp(this, 24), TvUi.dp(this, 6));
        bar.addView(TvUi.compactButton(this, "‹  Frame TV", this::finish),
                new LinearLayout.LayoutParams(TvUi.dp(this, 112), TvUi.dp(this, 40)));
        TextView title = TvUi.text(this, feature.title, 18, TvUi.TEXT);
        title.setPadding(TvUi.dp(this, 20), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(TvUi.compactButton(this, "Reload", this::reload),
                new LinearLayout.LayoutParams(TvUi.dp(this, 84), TvUi.dp(this, 40)));
        controls.addView(bar);
        status = TvUi.text(this, "Loading…", 14, TvUi.MUTED);
        status.setPadding(TvUi.dp(this, 24), 0, 0, TvUi.dp(this, 6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(status);
        // Controls overlay the page; they never reduce its viewport or change responsive breakpoints.
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP));
        setContentView(root);
    }

    @Override public void onResume() {
        super.onResume();
        active = true;
        if (surface != null && web == null) createRenderer();
        if (web != null) web.onResume();
    }
    @Override public void onPause() {
        active = false;
        // Destroy the renderer on focus handoff instead of relying on an asynchronous
        // JavaScript media pause. Also covers embedded media in cross-origin frames.
        destroyRenderer();
        super.onPause();
    }
    @Override public void onStop() { destroyRenderer(); super.onStop(); }
    @Override public void onDestroy() { destroyRenderer(); super.onDestroy(); }

    @SuppressLint("SetJavaScriptEnabled")
    private void createRenderer() {
        if (!active || isFinishing()) return;
        try {
            web = new WebView(this);
            web.setBackgroundColor(TvUi.BG);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSafeBrowsingEnabled(true);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    if (view == web) { loadFailed = false; showStatus("Loading…"); }
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return blockNavigation(request.getUrl().toString());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return blockNavigation(url);
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (view == web && active && !loadFailed && UrlPolicy.navigationAllowed(address, url)) {
                        status.setVisibility(View.GONE);
                        updateControls();
                        if (!menuRequested) web.requestFocus();
                        if (forecastCapture != null) forecastCapture.start(web, new TvPreferences(DashboardActivity.this).url(Feature.HOME));
                    }
                }
                @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.cancel();
                    loadFailed = true;
                    showStatus("Secure connection failed. Check the dashboard certificate.");
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        loadFailed = true;
                        showStatus("Dashboard unavailable. Check its address or network, then reload.");
                    }
                }
                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                    if (request.isForMainFrame()) {
                        loadFailed = true;
                        showStatus("Dashboard returned an error (" + response.getStatusCode() + ").");
                    }
                }
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    if (view == web) {
                        destroyRenderer();
                        loadFailed = true;
                        showStatus("Dashboard closed to recover resources. Select Reload to reopen it.");
                    }
                    return true;
                }
            });
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            surface.addView(web, new FrameLayout.LayoutParams(-1, -1));
            showStatus("Loading…");
            web.loadUrl(address);
        } catch (RuntimeException error) {
            destroyRenderer();
            loadFailed = true;
            showStatus("Android System WebView is unavailable. Update it in Google Play and reopen this view.");
        }
    }
    private boolean blockNavigation(String destination) {
        if (UrlPolicy.navigationAllowed(address, destination)) return false;
        menuRequested = true;
        showStatus("This link leaves your dashboard. Open it on another device.");
        return true;
    }
    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
        updateControls();
    }
    private void updateControls() {
        bar.setVisibility(menuRequested || loadFailed ? View.VISIBLE : View.GONE);
        controls.setVisibility(bar.getVisibility() == View.VISIBLE || status.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
    }
    private void reload() {
        menuRequested = false;
        loadFailed = false;
        destroyRenderer();
        createRenderer();
    }
    private void destroyRenderer() {
        if (forecastCapture != null) forecastCapture.stop();
        WebView old = web;
        web = null;
        if (old != null) {
            old.stopLoading();
            surface.removeView(old);
            old.setWebChromeClient(null);
            old.setWebViewClient(new WebViewClient());
            old.destroy();
        }
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            menuRequested = !menuRequested;
            if (!menuRequested && !loadFailed) status.setVisibility(View.GONE);
            updateControls();
            if (menuRequested) bar.getChildAt(0).requestFocus();
            else if (web != null) web.requestFocus();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    @Override public void onBackPressed() {
        if (menuRequested) {
            menuRequested = false;
            if (!loadFailed) status.setVisibility(View.GONE);
            updateControls();
            if (web != null) web.requestFocus();
        } else finish();
    }
}
