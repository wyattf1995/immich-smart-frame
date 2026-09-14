package com.wyattfleming.frametv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/** Small shared native-View styling kit. It deliberately has no theme or library dependency. */
public final class TvUi {
    public static final int BG = Color.rgb(8, 18, 36);
    public static final int PANEL = Color.rgb(20, 39, 65);
    public static final int TEXT = Color.rgb(242, 246, 250);
    public static final int MUTED = Color.rgb(176, 193, 210);
    public static final int ACCENT = Color.rgb(241, 177, 73);

    private TvUi() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static Button button(Context context, String label, final Runnable action) {
        Button view = new Button(context);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(16);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        view.setMinHeight(dp(context, 76));
        view.setMinWidth(dp(context, 172));
        view.setBackground(tileBackground(context));
        view.setContentDescription(label);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    public static Button compactButton(Context context, String label, final Runnable action) {
        Button view = button(context, label, action);
        view.setTextSize(14);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setMaxLines(2);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        view.setBackground(compactBackground(context));
        return view;
    }

    private static StateListDrawable compactBackground(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(context, Color.rgb(57, 81, 111), 10, ACCENT, 2));
        states.addState(new int[]{android.R.attr.state_focused},
                rounded(context, Color.rgb(44, 67, 96), 10, ACCENT, 2));
        states.addState(new int[]{},
                rounded(context, Color.argb(185, 15, 28, 47), 10, Color.argb(35, 255, 255, 255), 1));
        return states;
    }

    public static void window(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setBackgroundColor(BG);
    }

    private static StateListDrawable tileBackground(Context context) {
        GradientDrawable normal = rounded(context, PANEL, 14, Color.TRANSPARENT, 0);
        GradientDrawable focused = rounded(context, Color.rgb(44, 67, 96), 14, ACCENT, 3);
        GradientDrawable pressed = rounded(context, Color.rgb(57, 81, 111), 14, ACCENT, 3);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private static GradientDrawable rounded(Context context, int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radius));
        if (width > 0) drawable.setStroke(dp(context, width), stroke);
        return drawable;
    }
}
