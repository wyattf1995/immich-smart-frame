package com.wyattfleming.frametv.probe4k;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A launcher-only, standalone diagnostic. It deliberately makes no assertion that an accepted
 * 3840x2160 EGL buffer means the television panel is showing native 4K detail.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "Frame4KProbe";

    private ProbeSurfaceView glView;
    private TextView overlay;
    private ProbeMode mode = ProbeMode.HD;
    private boolean alternatePattern;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "lifecycle onCreate");
        logActivityStart();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        glView = new ProbeSurfaceView(this, this::onRendererStatus);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.rgb(238, 238, 238));
        overlay.setTextSize(16);
        overlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlay.setPadding(18, 12, 18, 12);
        overlay.setBackgroundColor(0xB0000000);
        overlay.setGravity(Gravity.START);
        FrameLayout.LayoutParams labelLayout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        root.addView(overlay, labelLayout);
        setContentView(root);
        applyMode();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "lifecycle onResume");
        glView.onResume();
    }

    @Override
    public void onPause() {
        Log.i(TAG, "lifecycle onPause");
        glView.onPause();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "lifecycle onDestroy");
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            mode = ProbeMode.fromRightSelection(key == KeyEvent.KEYCODE_DPAD_RIGHT);
            applyMode();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            alternatePattern = !alternatePattern;
            glView.setAlternatePattern(alternatePattern);
            updateOverlay("Pattern alignment: " + (alternatePattern ? "alternate" : "standard"));
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            overlay.setVisibility(overlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void applyMode() {
        Log.i(TAG, "requested_buffer=" + mode.width + "x" + mode.height);
        glView.setProbeMode(mode, alternatePattern);
        updateOverlay("Requested " + mode.displayName + "  " + mode.width + "x" + mode.height);
    }

    private void onRendererStatus(String status) {
        runOnUiThread(() -> updateOverlay(status));
    }

    private void updateOverlay(String status) {
        overlay.setText("FRAME 4K BUFFER PROBE — panel detail unverified\n"
                + status + "\n"
                + "Left: 1080p  Right: 4K  Center: pattern  Menu: overlay  Back: exit\n"
                + "Inspect stripes visually; buffer size alone is not proof of native panel detail.");
    }

    private void logActivityStart() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        Display display = getWindowManager().getDefaultDisplay();
        DisplayMetrics realMetrics = new DisplayMetrics();
        display.getRealMetrics(realMetrics);
        JSONObject record = new JSONObject();
        try {
            record.put("event", "activity_start");
            record.put("api", Build.VERSION.SDK_INT);
            record.put("logical_bounds", metrics.widthPixels + "x" + metrics.heightPixels);
            record.put("real_bounds", realMetrics.widthPixels + "x" + realMetrics.heightPixels);
            record.put("density", metrics.density);
            record.put("model", Build.MODEL);
            record.put("manufacturer", Build.MANUFACTURER);
            Log.i(TAG, record.toString());
        } catch (JSONException error) {
            Log.e(TAG, "failure activity_start_json", error);
        }
    }
}
