package com.gather.quotapocket;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared native controls and spacing for the app; the desktop widget keeps its own layout. */
final class AppUi {
    final Activity a;
    AppUi(Activity a) { this.a = a; }
    int dp(int n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(a); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout page() {
        LinearLayout v = column();
        int gutter = a.getResources().getConfiguration().screenWidthDp >= 600 ? 48 : 20;
        v.setPadding(dp(gutter), dp(20), dp(gutter), dp(28)); return v;
    }
    ScrollView scroll(LinearLayout body) {
        ScrollView v = new ScrollView(a); v.setFillViewport(true); v.setClipToPadding(false); v.addView(body); return v;
    }
    void insets(View root) {
        root.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i.consumeSystemWindowInsets();
        });
    }
    TextView text(LinearLayout parent, String value, int size, boolean primary, int top) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size);
        t.setTextColor(a.getColor(primary ? R.color.primary_text : R.color.secondary_text));
        t.setLineSpacing(dp(3), 1); add(parent, t, top); return t;
    }
    void title(LinearLayout parent, String title, String subtitle) {
        TextView t = text(parent, title, 28, true, 0);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (!subtitle.isEmpty()) text(parent, subtitle, 14, false, 8);
    }
    void section(LinearLayout parent, String title) { text(parent, title, 14, false, 24); }
    void back(LinearLayout parent, Runnable action) {
        Button b = button(parent, "‹  返回", false, action);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.border)), shape(R.color.background, 12), null));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams(); lp.width = -2; lp.topMargin = 0; b.setLayoutParams(lp);
    }
    LinearLayout card(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackground(shape(R.color.surface, 20)); add(parent, c, 12); return c;
    }
    Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
        Button b = new Button(a); b.setText(title); b.setTextSize(16); b.setAllCaps(false);
        b.setMinHeight(dp(52)); b.setMinimumWidth(0); b.setMinWidth(0);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setTextColor(a.getColor(primary ? R.color.background : R.color.mint));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.border)),
            shape(primary ? R.color.mint : R.color.button_surface, 14), null));
        b.setOnClickListener(v -> action.run()); add(parent, b, 12); return b;
    }
    Button row(LinearLayout parent, String title, String detail, Runnable action) {
        Button b = button(parent, title, false, action);
        b.setTextColor(a.getColor(R.color.primary_text)); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(a.getColor(R.color.border)), shape(R.color.surface, 16), null));
        b.setMinHeight(dp(68)); b.setLineSpacing(dp(4), 1); rowLabel(b, title, detail); return b;
    }
    void rowLabel(Button button, String title, String detail) {
        String heading = title + "  ›";
        android.text.SpannableString label = new android.text.SpannableString(heading + (detail.isEmpty() ? "" : "\n" + detail));
        label.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(), 0);
        if (!detail.isEmpty()) {
            label.setSpan(new android.text.style.RelativeSizeSpan(0.85f), heading.length() + 1, label.length(), 0);
            label.setSpan(new android.text.style.ForegroundColorSpan(a.getColor(R.color.secondary_text)), heading.length() + 1, label.length(), 0);
        }
        button.setText(label);
    }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(a.getColor(color)); d.setCornerRadius(dp(radius)); return d;
    }
    void add(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top); parent.addView(v, lp);
    }
}
