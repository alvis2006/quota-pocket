package com.gather.quotapocket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Separate overview and per-window editor keep the connection screen focused. */
public final class AlertSettingsActivity extends Activity {
    private String key;
    private AppUi ui;
    private TextView modeHint;
    private int nextId = 1000;
    private LinearLayout content, weighted, reserve;
    private CheckBox enabled, overEnabled, underEnabled;
    private RadioGroup modes;
    private EditText over, under, step, reservePercent, reserveHours;
    private final EditText[] weights = new EditText[7];
    private TextView permission, feedback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshOverview(); handler.postDelayed(this, 30_000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        key = getIntent().getStringExtra("plan");
        if (key != null) {
            try { QuotaAlerts.index(key); } catch (IllegalArgumentException e) { finish(); return; }
        }
        ui = new AppUi(this);
        LinearLayout root = ui.column(); ui.insets(root); setContentView(root);
        LinearLayout toolbar = ui.column(); toolbar.setPadding(dp(20), 0, dp(20), dp(8));
        ui.back(toolbar, this::leave); root.addView(toolbar);
        content = ui.page();
        ScrollView scroll = ui.scroll(content); scroll.setId(nextId++);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (key == null) overview();
        else {
            editor();
            LinearLayout footer = ui.column(); footer.setPadding(dp(20), 0, dp(20), dp(12)); footer.setBackgroundColor(getColor(R.color.surface));
            feedback = ui.text(footer, "", 14, false, 0); feedback.setVisibility(View.GONE);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            ui.button(footer, "儲存計劃", true, () -> {
                try {
                    UsagePlan edited = readPlan(); edited.validate(duration()); QuotaAlerts.savePlan(this, key, edited);
                    Toast.makeText(this, "使用計劃已儲存", Toast.LENGTH_SHORT).show(); finish();
                } catch (IllegalArgumentException e) { feedback.setText(e.getMessage()); feedback.setVisibility(View.VISIBLE); }
            });
            root.addView(footer);
        }
    }
    private void overview() {
        ui.title(content, "通知設定", "提醒偏離與重置，預設無聲、不震動。");
        LinearLayout switches = card(content);
        CheckBox master = check(switches, "啟用用量提醒", QuotaAlerts.settings(this).getBoolean("enabled", false));
        CheckBox reset = check(switches, "額度重置時通知", QuotaAlerts.settings(this).getBoolean("reset", true));
        text(switches, "開關立即儲存，僅監控已啟用的額度時段。", 13, false);
        master.setOnCheckedChangeListener((v, checked) -> {
            QuotaAlerts.settings(this).edit().putBoolean("enabled", checked).apply();
            if (!checked) QuotaAlerts.clearHistory(this);
            else {
                QuotaAlerts.evaluate(this);
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !QuotaAlerts.settings(this).getBoolean("permissionAsked", false)) allowNotifications();
            }
            refreshOverview();
        });
        reset.setOnCheckedChangeListener((v, checked) -> QuotaAlerts.settings(this).edit().putBoolean("reset", checked).apply());
        LinearLayout delivery = card(content);
        text(delivery, "通知狀態", 18, true);
        permission = text(delivery, "", 14, false);
        ui.button(delivery, "管理通知權限", true, this::allowNotifications);
        ui.button(delivery, "發送無聲測試通知", false, () -> {
            boolean sent = QuotaAlerts.test(this);
            feedback.setText(sent ? "已送出，請下拉通知列查看。" : "尚未允許通知，請先管理通知權限。");
            feedback.setVisibility(View.VISIBLE);
        });
        feedback = text(delivery, "", 14, true); feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.row(content, "沒有收到提醒？", "查看資料與更新條件", () -> new AlertDialog.Builder(this).setTitle("提醒需要新的有效資料")
            .setMessage("提醒跟隨額度更新。示範資料、超過 1 小時的資料、沒有重置時間的資料都不觸發提醒。\n\nClaude 雲端檔案需要電腦先產生新資料。首次接入不發重置通知，之後來源確認新週期才會通知。")
            .setPositiveButton("知道了", null).show());
    }
    private void editor() {
        UsagePlan p = QuotaAlerts.plan(this, key);
        ui.title(content, UsagePlan.NAMES[QuotaAlerts.index(key)], "設定這個時段的分配與提醒條件。");
        if (!QuotaAlerts.settings(this).getBoolean("enabled", false)) text(content, "通知尚未啟用；儲存後可到「通知設定」開啟。", 14, false);
        enabled = check(content, "監控這個額度時段", p.enabled);
        text(content, "1  選擇分配方式", 18, true);
        modes = new RadioGroup(this); modes.setId(nextId++); content.addView(modes);
        String[] titles = {"平均分配 · 預設", "按星期分配", "保留額度"};
        for (int i = 0; i < titles.length; i++) {
            RadioButton b = new RadioButton(this); b.setId(nextId++); b.setText(titles[i]); b.setTextSize(16); b.setMinHeight(dp(52));
            modes.addView(b);
        }
        modeHint = text(content, "", 14, false);
        weighted = card(content);
        text(weighted, "每天的分配比重", 18, true);
        text(weighted, "2 是 1 的兩倍；0 代表當天不安排。依手機時區與週期涵蓋時數分配。5 小時若在同一天，按時間平均分配；該天比重為 0 則不安排用量。", 14, false);
        String[] days = {"週一", "週二", "週三", "週四", "週五", "週六", "週日"};
        for (int i = 0; i < 7; i++) weights[i] = field(weighted, days[i] + "的比重（0–100）", p.weights[i], 0, 100);
        reserve = card(content);
        text(reserve, "留一部分，稍後再用", 18, true);
        reserveHours = field(reserve, "距重置前（小時）", p.reserveHours, 0.25, duration() / (double) UsagePlan.HOUR - 0.25);
        reservePercent = field(reserve, "屆時保留額度（%）", p.reserve, 1, 99);
        text(reserve, key.endsWith("week")
            ? "例如距重置 48 小時前保留 30%：此前平穩分配 70%，最後 48 小時再分配剩下的 30%。目標會隨每次新週期移動。"
            : "例如距重置 1 小時前保留 30%：前 4 小時平穩分配 70%，最後 1 小時再分配剩下的 30%。目標會隨每次新週期移動。", 14, false);
        int selected = p.mode.equals("weighted") ? 1 : p.mode.equals("reserve") ? 2 : 0;
        modes.check(modes.getChildAt(selected).getId());
        modes.setOnCheckedChangeListener((group, id) -> showMode()); showMode();
        text(content, "2  設定提醒條件", 18, true);
        LinearLayout conditions = card(content);
        overEnabled = check(conditions, "用太多時提醒", p.overEnabled);
        over = field(conditions, "超用門檻（百分點）", p.over, 1, 100);
        underEnabled = check(conditions, "用太少時提醒", p.underEnabled);
        under = field(conditions, "少用門檻（百分點）", p.under, 1, 100);
        step = field(conditions, "再次提醒間距（百分點）", p.step, 1, 100);
        text(content, "門檻 10、間距 10：偏離達 10、20、30 個百分點時各提醒一次。相同級別不重複通知。", 14, false);
        overEnabled.setOnCheckedChangeListener((b, checked) -> over.setEnabled(checked));
        underEnabled.setOnCheckedChangeListener((b, checked) -> under.setEnabled(checked));
        over.setEnabled(p.overEnabled); under.setEnabled(p.underEnabled);
        ui.row(content, "查看目前計劃進度", "依已儲存的計劃與最新有效資料", () -> new AlertDialog.Builder(this)
            .setTitle("目前進度").setMessage(QuotaAlerts.status(this, key)).setPositiveButton("知道了", null).show());
        text(content, "修改後會重新記錄提醒級別。", 13, false);
    }
    private long duration() { return key.endsWith("week") ? UsagePlan.WEEK : UsagePlan.FIVE_HOURS; }
    private void showMode() {
        int selected = modes.indexOfChild(modes.findViewById(modes.getCheckedRadioButtonId()));
        modeHint.setText(selected == 1 ? "按星期安排不同用量，工作日與休息日可以不同。" : selected == 2 ? "先留下一部分額度，接近重置時再用。" : "從本週期開始到重置，平均分配可用額度。");
        weighted.setVisibility(modes.getCheckedRadioButtonId() == modes.getChildAt(1).getId() ? View.VISIBLE : View.GONE);
        reserve.setVisibility(modes.getCheckedRadioButtonId() == modes.getChildAt(2).getId() ? View.VISIBLE : View.GONE);
    }
    private UsagePlan readPlan() {
        UsagePlan p = QuotaAlerts.plan(this, key);
        p.enabled = enabled.isChecked(); p.overEnabled = overEnabled.isChecked(); p.underEnabled = underEnabled.isChecked();
        int selected = modes.indexOfChild(modes.findViewById(modes.getCheckedRadioButtonId()));
        p.mode = selected == 1 ? "weighted" : selected == 2 ? "reserve" : "average";
        if (p.overEnabled) p.over = value(over, 1, 100);
        if (p.underEnabled) p.under = value(under, 1, 100);
        p.step = value(step, 1, 100);
        if (p.mode.equals("weighted")) for (int i = 0; i < 7; i++) p.weights[i] = value(weights[i], 0, 100);
        if (p.mode.equals("reserve")) {
            p.reserveHours = value(reserveHours, 0.25, duration() / (double) UsagePlan.HOUR - 0.25);
            p.reserve = value(reservePercent, 1, 99);
        }
        return p;
    }
    private void leave() {
        if (key == null) { finish(); return; }
        boolean dirty;
        try { dirty = !readPlan().json().equals(QuotaAlerts.plan(this, key).json()); }
        catch (IllegalArgumentException e) { dirty = true; }
        if (!dirty) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("放棄尚未儲存的計劃？").setMessage("返回後會保留上次儲存的設定。")
            .setNegativeButton("繼續編輯", null).setPositiveButton("放棄修改", (d, w) -> finish()).show();
    }
    @Override public void onBackPressed() { leave(); }
    @Override protected void onStart() { super.onStart(); handler.post(refresh); }
    @Override protected void onStop() { handler.removeCallbacksAndMessages(null); super.onStop(); }
    private void refreshOverview() {
        if (permission != null) permission.setText(QuotaAlerts.permissionText(this));
    }
    private void allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && (!QuotaAlerts.settings(this).getBoolean("permissionAsked", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            QuotaAlerts.settings(this).edit().putBoolean("permissionAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 40); return;
        }
        QuotaAlerts.channels(this);
        startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, QuotaAlerts.CHANNEL));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 40) { refreshOverview(); QuotaAlerts.evaluate(this); }
    }
    @Override protected void onResume() { super.onResume(); refreshOverview(); QuotaAlerts.evaluate(this); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout l = column(); l.setPadding(dp(16), dp(4), dp(16), dp(16)); l.setBackground(surface());
        add(parent, l, 16); return l;
    }
    private TextView text(LinearLayout parent, String title, int size, boolean primary) {
        TextView v = new TextView(this); v.setText(title); v.setTextSize(size); v.setLineSpacing(dp(3), 1);
        v.setTextColor(getColor(primary ? R.color.primary_text : R.color.secondary_text)); add(parent, v, 14); return v;
    }
    private CheckBox check(LinearLayout parent, String title, boolean checked) {
        CheckBox b = new CheckBox(this); b.setId(nextId++); b.setText(title); b.setTextSize(16); b.setMinHeight(dp(52)); b.setChecked(checked);
        add(parent, b, 8); return b;
    }
    private EditText field(LinearLayout parent, String label, double number, double min, double max) {
        LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView labelView = new TextView(this); labelView.setText(label); labelView.setTextSize(14);
        labelView.setTextColor(getColor(R.color.primary_text)); labelView.setPadding(0, dp(8), dp(12), dp(8));
        row.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1));
        EditText v = new EditText(this); v.setId(nextId++); labelView.setLabelFor(v.getId());
        v.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        v.setSingleLine(true); v.setTextSize(16); v.setMinHeight(dp(52)); v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setText(number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number));
        GradientDrawable fieldBackground = ui.shape(R.color.background, 12); fieldBackground.setStroke(dp(1), getColor(R.color.muted_text)); v.setBackground(fieldBackground);
        int fieldWidth = Math.round(Math.min(144, 92 * getResources().getConfiguration().fontScale));
        row.addView(v, new LinearLayout.LayoutParams(dp(fieldWidth), -2)); add(parent, row, 12);
        v.setOnFocusChangeListener((view, focused) -> { if (!focused && v.isShown() && v.isEnabled()) {
            try { value(v, min, max); } catch (IllegalArgumentException ignored) {}
        } });
        return v;
    }
    private double value(EditText v, double min, double max) {
        try {
            double n = Double.parseDouble(v.getText().toString().trim().replace(',', '.'));
            if (!Double.isFinite(n) || n < min || n > max) throw new NumberFormatException();
            v.setError(null); return n;
        } catch (NumberFormatException e) {
            String error = "請輸入 " + min + "–" + max + " 之間的數值";
            v.setError(error); throw new IllegalArgumentException(error);
        }
    }
    private void add(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top); parent.addView(v, lp);
    }
    private GradientDrawable surface() {
        GradientDrawable d = new GradientDrawable(); d.setColor(getColor(R.color.surface)); d.setCornerRadius(dp(16)); return d;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
