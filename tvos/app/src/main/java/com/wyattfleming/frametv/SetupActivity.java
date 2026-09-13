package com.wyattfleming.frametv;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.EnumMap;
import java.util.Map;

public final class SetupActivity extends Activity {
    private final Map<Feature, EditText> inputs = new EnumMap<>(Feature.class);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TvUi.window(this);
        TvPreferences preferences = new TvPreferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = TvUi.dp(this, 42);
        content.setPadding(pad, pad, pad, pad);
        content.addView(TvUi.text(this, "Your home, on TV", 30, TvUi.TEXT));
        TextView description = TvUi.text(this, "Add your photo and home dashboard addresses. Each is optional. Use the dashboard’s own sign-in page; sign-in through Google or other external providers isn’t supported yet. Keep passwords out of the address.", 16, TvUi.MUTED);
        description.setPadding(0, TvUi.dp(this, 12), 0, TvUi.dp(this, 18));
        content.addView(description);
        for (Feature feature : Feature.values()) {
            content.addView(TvUi.text(this, feature.title, 18, TvUi.TEXT));
            EditText field = new EditText(this);
            field.setId(View.generateViewId());
            field.setText(preferences.url(feature));
            field.setHint("https://your-dashboard.example/" );
            field.setTextColor(TvUi.TEXT);
            field.setHintTextColor(TvUi.MUTED);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            field.setSelectAllOnFocus(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, TvUi.dp(this, 56));
            lp.bottomMargin = TvUi.dp(this, 14);
            content.addView(field, lp);
            inputs.put(feature, field);
        }
        TextView message = TvUi.text(this, "", 16, TvUi.ACCENT);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(message);
        content.addView(TvUi.button(this, "Save dashboards", () -> {
            Map<Feature, String> values = new EnumMap<>(Feature.class);
            for (Map.Entry<Feature, EditText> entry : inputs.entrySet()) {
                try { values.put(entry.getKey(), UrlPolicy.validate(entry.getValue().getText().toString())); }
                catch (IllegalArgumentException error) {
                    message.setText(entry.getKey().title + ": " + error.getMessage());
                    entry.getValue().requestFocus();
                    return;
                }
            }
            preferences.saveUrls(values);
            finish();
        }));
        content.addView(TvUi.button(this, "Back to Frame TV", this::finish));
        scroll.addView(content);
        setContentView(scroll);
    }
}
