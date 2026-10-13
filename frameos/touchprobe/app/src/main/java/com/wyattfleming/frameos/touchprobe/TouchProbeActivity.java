package com.wyattfleming.frameos.touchprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A self-contained, no-network Android 6 touchscreen and WebView capability probe. */
public final class TouchProbeActivity extends Activity {
    private static final int DARK = Color.rgb(16, 21, 16);
    private static final int PANEL = Color.rgb(30, 42, 30);
    private static final int TEXT = Color.rgb(244, 247, 242);
    private static final int GREEN = Color.rgb(111, 207, 117);
    private final int paddingDp = 18;
    private WebView activeWebView;
    private String webViewError;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showDiagnostics();
    }

    private void showDiagnostics() {
        destroyActiveWebView();
        LinearLayout page = page();
        page.addView(title("FrameOS Touch Probe"));
        page.addView(body("This stays on the device. It uses no network, credentials, or persistent diagnostic log."));
        page.addView(button("Open offline WebView check", new View.OnClickListener() {
            @Override public void onClick(View view) { showWebViewCheck(); }
        }));
        page.addView(section("Device capabilities"));
        page.addView(body(diagnosticReport()));
        if (webViewError != null) {
            page.addView(section("Offline WebView check"));
            page.addView(body(webViewError));
        }
        page.addView(section("Touch visualizer"));
        page.addView(body("Touch the panel with one or more fingers. The count and coordinates update while you touch it."));
        TouchVisualizer visualizer = new TouchVisualizer(this);
        page.addView(visualizer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        page.addView(body("A successful probe only confirms this APK ran. It does not prove rooting, ADB/USB access, bootloader access, or FrameOS compatibility."));
        setContentView(scroll(page));
    }

    private void showWebViewCheck() {
        destroyActiveWebView();
        webViewError = null;
        try {
            FrameLayout root = new FrameLayout(this);
            root.setBackgroundColor(DARK);
            WebView webView = new WebView(this);
            activeWebView = webView;
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(false);
            settings.setDatabaseEnabled(false);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(false);
            settings.setAllowFileAccessFromFileURLs(false);
            settings.setAllowUniversalAccessFromFileURLs(false);
            webView.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return !"file:///android_asset/webview-capability.html".equals(url);
                }
            });
            webView.loadUrl("file:///android_asset/webview-capability.html");
            root.addView(webView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Button back = button("Back to diagnostics", new View.OnClickListener() {
                @Override public void onClick(View view) { showDiagnostics(); }
            });
            FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.TOP | Gravity.END);
            buttonParams.setMargins(dp(12), dp(12), dp(12), dp(12));
            root.addView(back, buttonParams);
            applySystemBarInsets(root);
            setContentView(root);
        } catch (RuntimeException error) {
            destroyActiveWebView();
            webViewError = "Offline WebView could not start: " + error.getClass().getSimpleName();
            showDiagnostics();
        }
    }

    @Override protected void onDestroy() {
        destroyActiveWebView();
        super.onDestroy();
    }

    private void destroyActiveWebView() {
        WebView webView = activeWebView;
        activeWebView = null;
        if (webView == null) return;
        try {
            webView.stopLoading();
            webView.clearHistory();
            ViewParent parent = webView.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(webView);
            webView.destroy();
        } catch (RuntimeException ignored) {
            // The diagnostic screen reports provider initialization failures without crashing.
        }
    }

    private String diagnosticReport() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        PackageManager packages = getPackageManager();
        return "Android release: " + Build.VERSION.RELEASE + "\n"
                + "API level: " + Build.VERSION.SDK_INT + "\n"
                + "Security patch: " + Build.VERSION.SECURITY_PATCH + "\n"
                + "Supported ABIs: " + join(Build.SUPPORTED_ABIS) + "\n"
                + "32-bit ABIs: " + join(Build.SUPPORTED_32_BIT_ABIS) + "\n"
                + "64-bit ABIs: " + join(Build.SUPPORTED_64_BIT_ABIS) + "\n"
                + "Display: " + metrics.widthPixels + " × " + metrics.heightPixels + " px\n"
                + "Density: " + metrics.density + " (" + metrics.densityDpi + " dpi)\n"
                + "Smallest width: " + getResources().getConfiguration().smallestScreenWidthDp + " dp\n"
                + "App memory class: " + manager.getMemoryClass() + " MiB\n"
                + "Available memory: " + (memory.availMem / (1024L * 1024L)) + " MiB\n"
                + "Low-memory signal: " + memory.lowMemory + "\n"
                + "Touchscreen: " + feature(packages, PackageManager.FEATURE_TOUCHSCREEN) + "\n"
                + "Multitouch: " + (feature(packages, PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH)
                || feature(packages, PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)
                || feature(packages, PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND));
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) return "none reported";
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) report.append(", ");
            report.append(values[i]);
        }
        return report.toString();
    }

    private static boolean feature(PackageManager packages, String name) {
        return packages.hasSystemFeature(name);
    }

    private ScrollView scroll(LinearLayout page) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(DARK);
        applySystemBarInsets(scroll);
        scroll.addView(page);
        return scroll;
    }

    private void applySystemBarInsets(final View view) {
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View target, WindowInsets insets) {
                target.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        view.requestApplyInsets();
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        page.setBackgroundColor(DARK);
        return page;
    }

    private TextView title(String value) {
        TextView view = text(value, 30);
        view.setPadding(0, 0, 0, dp(10));
        return view;
    }

    private TextView section(String value) {
        TextView view = text(value, 22);
        view.setTextColor(GREEN);
        view.setPadding(0, dp(22), 0, dp(8));
        return view;
    }

    private TextView body(String value) {
        TextView view = text(value, 17);
        view.setLineSpacing(dp(3), 1f);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(TEXT);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(18);
        view.setTextColor(Color.BLACK);
        view.setBackgroundColor(GREEN);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        layout.setMargins(0, 0, 0, dp(12));
        view.setLayoutParams(layout);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class TouchVisualizer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] xs = new float[10];
        private final float[] ys = new float[10];
        private int pointerCount;
        private String status = "Waiting for touch";

        TouchVisualizer(Context context) {
            super(context);
            setBackgroundColor(PANEL);
            setContentDescription("Multi-touch visualizer");
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                status = "Last touch at x=" + Math.round(event.getX()) + " y=" + Math.round(event.getY())
                        + " · action " + actionName(action);
                clearActivePoints();
                ViewParent parent = getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                if (action == MotionEvent.ACTION_UP) performClick();
            } else {
                copyActivePoints(event, action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1);
                status = pointSummary(action);
            }
            invalidate();
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        private void copyActivePoints(MotionEvent event, int excludedIndex) {
            pointerCount = 0;
            for (int index = 0; index < event.getPointerCount() && pointerCount < xs.length; index++) {
                if (index == excludedIndex) continue;
                xs[pointerCount] = event.getX(index);
                ys[pointerCount] = event.getY(index);
                pointerCount += 1;
            }
        }

        private void clearActivePoints() {
            for (int index = 0; index < pointerCount; index++) {
                xs[index] = 0f;
                ys[index] = 0f;
            }
            pointerCount = 0;
        }

        private String pointSummary(int action) {
            StringBuilder result = new StringBuilder();
            result.append(pointerCount).append(pointerCount == 1 ? " touch point" : " touch points");
            for (int index = 0; index < pointerCount; index++) {
                result.append(" · ").append(index + 1).append(": x=")
                        .append(Math.round(xs[index])).append(" y=").append(Math.round(ys[index]));
            }
            return result.append(" · action ").append(actionName(action)).toString();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(TEXT);
            paint.setTextSize(dp(18));
            canvas.drawText(status, dp(16), dp(30), paint);
            paint.setColor(GREEN);
            for (int index = 0; index < pointerCount; index++) {
                canvas.drawCircle(xs[index], ys[index], dp(26), paint);
                paint.setColor(Color.BLACK);
                paint.setTextSize(dp(16));
                canvas.drawText(String.valueOf(index + 1), xs[index] - dp(5), ys[index] + dp(6), paint);
                paint.setColor(GREEN);
            }
        }

        private String actionName(int action) {
            switch (action) {
                case MotionEvent.ACTION_DOWN: return "down";
                case MotionEvent.ACTION_POINTER_DOWN: return "pointer down";
                case MotionEvent.ACTION_MOVE: return "move";
                case MotionEvent.ACTION_POINTER_UP: return "pointer up";
                case MotionEvent.ACTION_UP: return "up";
                case MotionEvent.ACTION_CANCEL: return "cancel";
                default: return "other";
            }
        }
    }
}
