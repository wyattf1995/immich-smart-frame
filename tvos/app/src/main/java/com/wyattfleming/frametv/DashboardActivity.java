package com.wyattfleming.frametv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
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
    private boolean active;
    private boolean loadFailed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        try { feature = Feature.valueOf(getIntent().getStringExtra("feature")); }
        catch (IllegalArgumentException | NullPointerException error) { finish(); return; }
        address = new TvPreferences(this).url(feature);
        if (address.isEmpty()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(TvUi.dp(this, 24), TvUi.dp(this, 6), TvUi.dp(this, 24), TvUi.dp(this, 6));
        bar.addView(TvUi.compactButton(this, "‹  Frame TV", this::finish),
                new LinearLayout.LayoutParams(TvUi.dp(this, 112), TvUi.dp(this, 40)));
        TextView title = TvUi.text(this, feature.title, 18, TvUi.TEXT);
        title.setPadding(TvUi.dp(this, 20), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(TvUi.compactButton(this, "Reload", this::reload),
                new LinearLayout.LayoutParams(TvUi.dp(this, 84), TvUi.dp(this, 40)));
        root.addView(bar);
        status = TvUi.text(this, "Loading…", 14, TvUi.MUTED);
        status.setPadding(TvUi.dp(this, 24), 0, 0, TvUi.dp(this, 6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        surface = new FrameLayout(this);
        root.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
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
                    if (view == web && active && !loadFailed && UrlPolicy.navigationAllowed(address, url)) status.setVisibility(View.GONE);
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
            showStatus("Android System WebView is unavailable. Update it in Google Play and reopen this view.");
        }
    }
    private boolean blockNavigation(String destination) {
        if (UrlPolicy.navigationAllowed(address, destination)) return false;
        showStatus("This link leaves your dashboard. Open it on another device.");
        return true;
    }
    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }
    private void reload() { destroyRenderer(); createRenderer(); }
    private void destroyRenderer() {
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
    @Override public void onBackPressed() { finish(); }
}
