package com.gather.quotapocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT = 20, LINK = 21, PREVIEW_HEIGHT = 170;
    private QuotaStore store;
    private AppUi ui;
    private FrameLayout pages;
    private final ScrollView[] screens = new ScrollView[3];
    private final Button[] tabs = new Button[3];
    private final Button[] planRows = new Button[4];
    private LinearLayout claudeCard, codexCard;
    private Button refreshButton, claudeConnection, codexConnection, reminders;
    private TextView homeStatus;
    private int selectedTab;
    private String renderedData = "";
    private TextView feedback;
    private EditText endpoint, token;
    private Button liveButton;
    private LinearLayout advancedPanel;
    private Button advancedToggle;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> handler.post(this::render);
    private final Runnable tick = new Runnable() {
        @Override public void run() { render(); if (store.refreshable() && !LiveSyncService.active(MainActivity.this)) RefreshJob.request(MainActivity.this); handler.postDelayed(this, 60_000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new QuotaStore(this); ui = new AppUi(this);
        LinearLayout root = ui.column(); ui.insets(root); setContentView(root);
        pages = new FrameLayout(this); root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout home = page(0), plans = page(1), settings = page(2);
        buildHome(home); buildPlans(plans); buildSettings(settings);
        feedback = ui.text(root, "", 14, false, 0);
        feedback.setPadding(dp(20), dp(8), dp(20), dp(8)); feedback.setVisibility(View.GONE);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { feedback.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackgroundColor(getColor(R.color.surface)); root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        String[] labels = {"總覽", "使用計劃", "設定"};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            Button button = new Button(this); tabs[i] = button;
            button.setText(labels[i]); button.setTextSize(15); button.setAllCaps(false);
            button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(52));
            button.setPadding(dp(4), dp(12), dp(4), dp(12));
            button.setOnClickListener(v -> { feedback.setText(""); selectTab(tab); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(4), 0, dp(4), 0); nav.addView(button, lp);
        }
        if (saved != null && saved.getBoolean("advanced")) {
            advancedPanel.setVisibility(View.VISIBLE); ui.rowLabel(advancedToggle, "收起進階連線", "自訂 HTTPS 額度服務");
        }
        selectTab(saved == null ? 0 : saved.getInt("tab", 0)); render();
    }
    private LinearLayout page(int index) {
        LinearLayout body = ui.page(); screens[index] = ui.scroll(body); screens[index].setId(700 + index);
        pages.addView(screens[index], new FrameLayout.LayoutParams(-1, -1)); return body;
    }
    private void selectTab(int index) {
        selectedTab = Math.max(0, Math.min(2, index));
        for (int i = 0; i < 3; i++) {
            screens[i].setVisibility(i == selectedTab ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(i == selectedTab);
            tabs[i].setTextColor(getColor(i == selectedTab ? R.color.mint : R.color.secondary_text));
            tabs[i].setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.border)),
                ui.shape(i == selectedTab ? R.color.button_surface : R.color.surface, 12), null));
        }
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        render();
    }
    private void buildHome(LinearLayout body) {
        ui.title(body, "額度總覽", "Claude 與 Codex，剩餘用量一眼看清。");
        homeStatus = ui.text(body, "", 14, false, 16);
        claudeCard = ui.card(body); codexCard = ui.card(body);
        refreshButton = ui.button(body, "立即更新", true, () -> {
            if (store.refreshable()) { RefreshJob.request(this); render(); }
            else { selectTab(2); feedback.setText("先連接帳號或額度檔案，就能自動更新。"); }
        });
        ui.row(body, "桌面小工具", "預覽與加入桌面", this::showWidget);
    }
    private void buildPlans(LinearLayout body) {
        ui.title(body, "使用計劃", "選擇額度時段，安排你的使用節奏。");
        reminders = ui.row(body, "用量提醒", "", () -> startActivity(new Intent(this, AlertSettingsActivity.class)));
        for (int provider = 0; provider < 2; provider++) {
            ui.section(body, provider == 0 ? "CLAUDE" : "CODEX");
            for (int window = 0; window < 2; window++) {
                final int i = provider * 2 + window;
                planRows[i] = ui.row(body, window == 0 ? "5 小時額度" : "每週額度", "", () ->
                    startActivity(new Intent(this, AlertSettingsActivity.class).putExtra("plan", UsagePlan.KEYS[i])));
            }
        }
        ui.row(body, "提醒怎麼運作？", "超用、少用與重置的判斷方式", () -> info("提醒規則",
            "比較資料時間下的計劃已用與實際已用。計劃 40%、實際 55%，就是超用 15 個百分點。\n\n偏離加劇才再次提醒；同一週期已提醒的級別不重複通知。\n\n來源確認新的額度週期才發重置通知。示範、過期或缺少重置時間的資料不觸發提醒。"));
    }
    private void buildSettings(LinearLayout body) {
        ui.title(body, "設定", "管理連線、更新方式與通知。");
        ui.section(body, "資料連線");
        codexConnection = ui.row(body, "Codex", "", () -> { RefreshJob.cancel(this); startActivity(new Intent(this, CodexLoginActivity.class)); });
        claudeConnection = ui.row(body, "Claude", "", this::showClaudeDialog);
        ui.section(body, "更新與通知");
        liveButton = ui.row(body, "更新頻率", "", () -> new AlertDialog.Builder(this).setTitle("更新頻率")
            .setMessage("一般模式約每 15 分鐘背景更新；App 開啟時約每分鐘更新。\n\n每分鐘模式會顯示常駐通知，每次約可持續 6 小時；系統、省電或網路可能延後更新。")
            .setNegativeButton("返回", null).setPositiveButton(LiveSyncService.active(this) ? "改用一般模式" : "開啟每分鐘模式", (d,w) -> toggleLive()).show());
        ui.row(body, "通知設定", "用量提醒、重置通知與無聲測試", () -> startActivity(new Intent(this, AlertSettingsActivity.class)));
        ui.section(body, "資料管理");
        ui.row(body, "匯入額度檔案", "讀取一次額度快照", this::pickImport);
        LinearLayout advanced = ui.card(body); advancedPanel = advanced; advanced.setVisibility(View.GONE); advanced.setId(710);
        // Keep connection fields alive while switching tabs; never refill an in-progress edit during sync.
        ui.text(advanced, "自訂額度來源", 18, true, 0);
        ui.text(advanced, "僅供自行架設的額度服務使用。一般 Codex 登入不需要填寫。", 14, false, 8);
        endpoint = field(advanced, "HTTPS 網址", "https://your-server.example/quota", false, 101); endpoint.setText(store.endpoint());
        token = field(advanced, "連線密碼（選填）", "額度服務的存取密碼", true, 102);
        ui.text(advanced, "留空會清除原密碼。請勿填入模型 API 金鑰。", 13, false, 8);
        ui.button(advanced, "儲存並更新", true, () -> {
            try {
                String url = endpoint.getText().toString().trim(); QuotaStore.validateEndpoint(url);
                RefreshJob.cancel(this); store.connect(url, token.getText().toString().trim());
                token.setText(""); token.clearFocus(); endpoint.setError(null); RefreshJob.request(this); sourceChanged();
                feedback.setText("已儲存，正在連接額度來源。");
            } catch (IllegalArgumentException e) { endpoint.setError(e.getMessage()); endpoint.requestFocus(); }
            catch (Exception e) { feedback.setText("設定未儲存，請確認網址與手機的加密儲存功能。"); }
        });
        advancedToggle = ui.row(body, "進階連線", "自訂 HTTPS 額度服務", () -> {});
        body.removeView(advanced); body.addView(advanced);
        advancedToggle.setOnClickListener(v -> {
            boolean expand = advanced.getVisibility() != View.VISIBLE; advanced.setVisibility(expand ? View.VISIBLE : View.GONE);
            ui.rowLabel(advancedToggle, expand ? "收起進階連線" : "進階連線", "自訂 HTTPS 額度服務");
            if (expand) advanced.post(() -> screens[2].smoothScrollTo(0, advanced.getTop()));
        });
        ui.section(body, "其他");
        ui.row(body, "預覽示範資料", "會替換目前的連線與額度", () -> confirmReplace(() -> {
            RefreshJob.cancel(this); store.local(QuotaData.demo()); sourceChanged(); selectTab(0); feedback.setText("目前顯示示範資料。");
        }));
        ui.row(body, "清除資料與連線", "移除本機快照與登入，保留使用計劃", () -> new AlertDialog.Builder(this).setTitle("清除資料與連線？")
            .setMessage("會移除登入、額度快照與連線密碼。使用計劃會保留。")
            .setNegativeButton("取消", null).setPositiveButton("清除", (d,w) -> {
                RefreshJob.cancel(this); releaseClaude(); store.clear(); endpoint.setText(""); token.setText(""); sourceChanged(); feedback.setText("資料與連線已清除。");
            }).show());
        ui.text(body, "額度口袋 0.5.0 · 非官方工具", 13, false, 24);
    }
    private void info(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
    }
    private void showWidget() {
        LinearLayout body = ui.page();
        FrameLayout preview = new FrameLayout(this);
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(PREVIEW_HEIGHT)));
        int width = getResources().getConfiguration().screenWidthDp - 88;
        preview.addView(QuotaWidget.views(this, width, PREVIEW_HEIGHT).apply(this, preview));
        ui.text(body, "外圈是 5 小時，內圈是每週；圓心顯示剩下較少的額度。", 14, false, 12);
        new AlertDialog.Builder(this).setTitle("桌面小工具").setView(ui.scroll(body)).setNegativeButton("返回", null)
            .setPositiveButton("加入桌面", (d,w) -> {
                AppWidgetManager manager = AppWidgetManager.getInstance(this);
                if (manager.isRequestPinAppWidgetSupported()) { manager.requestPinAppWidget(new ComponentName(this, QuotaWidget.class), null, null); feedback.setText("請在桌面啟動器的提示中確認加入。"); }
                else info("加入桌面小工具", "長按桌面空白處 → 小工具 → 額度口袋，再拖到桌面。建議 4 × 2 或 5 × 2 格。");
            }).show();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", selectedTab); out.putBoolean("advanced", advancedPanel.getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed() { if (selectedTab != 0) selectTab(0); else super.onBackPressed(); }
    private void confirmReplace(Runnable action) {
        if (store.mode().equals("empty") || store.mode().equals("demo")) action.run();
        else new AlertDialog.Builder(this).setTitle("切換為示範資料？").setMessage("目前的資料與連線設定會被替換。")
            .setNegativeButton("取消", null).setPositiveButton("切換", (d, w) -> action.run()).show();
    }
    private void pickImport() { startActivityForResult(jsonPicker(), IMPORT); }
    private Intent jsonPicker() {
        // Drive may label a .json upload as text or binary, so accept those too; the parser decides.
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
    }
    private void showClaudeDialog() {
        boolean linked = store.claudeLinked();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("連接 Claude 額度")
            .setMessage("選擇雲端硬碟中的 Claude 額度檔案，App 會在更新時重新讀取。\n\n準備方式：在電腦上的 Claude Code 設定額度匯出，將 claude-quota.json 存到雲端同步資料夾，再於這裡選取。\n\n只有電腦產生新檔案後，手機才有新額度。已登入的 Codex 不受影響。")
            .setNegativeButton("關閉", null);
        if (linked) dialog.setPositiveButton("重新選擇檔案", (d, w) -> startActivityForResult(linkPicker(), LINK))
            .setNeutralButton("停止自動讀取", (d, w) -> { releaseClaude(); store.unlinkClaude(); RefreshJob.schedule(this); sourceChanged(); feedback.setText("已停止讀取 Claude 檔案，保留最後一份資料。"); });
        else dialog.setPositiveButton("選擇檔案", (d, w) -> startActivityForResult(linkPicker(), LINK))
            .setNeutralButton("只匯入一次", (d, w) -> pickImport());
        dialog.show();
    }
    private Intent linkPicker() {
        return jsonPicker().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }
    private void releaseClaude() {
        if (!store.claudeLinked()) return;
        try { getContentResolver().releasePersistableUriPermission(Uri.parse(store.claudeUri()), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) {}
    }
    private void linkClaude(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException e) { feedback.setText("這個檔案來源不允許 App 持續讀取。請改選 Google Drive 中的檔案，或用「只匯入一次」。"); return; }
        if (store.claudeLinked() && !store.claudeUri().equals(uri.toString())) releaseClaude();
        store.linkClaude(uri.toString());
        feedback.setText("正在讀取 Claude 檔案…");
        io.submit(() -> {
            ClaudeFile.refresh(this, true);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                RefreshJob.schedule(this); sourceChanged();
                String error = store.prefs.getString("claude_error", "");
                feedback.setText(error.isEmpty() ? "已連接。之後會自動讀取最新的 Claude 額度。" : error + "。已記住檔案，下次更新會再試。");
            });
        });
    }
    private void sourceChanged() { render(); QuotaWidget.renderAll(this); }
    private void render() {
        if (ui == null || refreshButton == null || reminders == null || codexConnection == null || isDestroyed()) return;
        QuotaData data = store.data();
        String signature = (data == null ? "" : data.json) + store.error() + store.prefs.getString("claude_error", "")
            + store.mode() + store.claudeUri() + System.currentTimeMillis() / 60_000;
        if (!signature.equals(renderedData)) {
            renderedData = signature;
            QuotaData separate = store.claudeSnapshot();
            QuotaData base = null; try { base = new QuotaData(store.prefs.getString("data", "")); } catch (Exception ignored) {}
            long claudeTime = separate != null && (base == null || base.claude == null) ? separate.generatedAt : data == null ? 0 : data.generatedAt;
            providerCard(claudeCard, "Claude", data == null ? null : data.claude, claudeTime, data != null && data.demo,
                store.prefs.getString("claude_error", ""), R.color.orange, this::showClaudeDialog);
            providerCard(codexCard, "Codex", data == null ? null : data.codex, data == null ? 0 : data.generatedAt, data != null && data.demo,
                store.error(), R.color.mint, () -> { RefreshJob.cancel(this); startActivity(new Intent(this, CodexLoginActivity.class)); });
        }
        homeStatus.setText(data != null && data.demo ? "示範資料 · 不代表真實額度" : store.loading() ? "正在更新額度…" : "顯示剩餘額度 · 更新時間見各服務");
        refreshButton.setText(store.loading() ? "正在更新…" : store.refreshable() ? "立即更新" : "連接資料來源");
        refreshButton.setEnabled(!store.loading()); refreshButton.setAlpha(store.loading() ? 0.6f : 1f);
        ui.rowLabel(codexConnection, "Codex", store.mode().equals("codex") ? "已登入 · 點按重新連接" : store.mode().equals("remote") ? "使用自訂來源" : "手機登入連接");
        ui.rowLabel(claudeConnection, "Claude", store.claudeLinked() ? "已連接雲端檔案" : store.mode().equals("remote") && data != null && data.claude != null ? "使用自訂來源" : store.claudeSnapshot() != null ? "使用匯入快照" : "連接雲端額度檔案");
        ui.rowLabel(liveButton, "更新頻率", LiveSyncService.active(this) ? "每分鐘模式 · 已開啟" : "一般背景更新");
        boolean on = QuotaAlerts.settings(this).getBoolean("enabled", false);
        ui.rowLabel(reminders, "用量提醒", on ? QuotaAlerts.permitted(this) ? "已開啟 · 點按管理通知" : "待允許系統通知" : "尚未開啟 · 點按設定");
        for (int i = 0; i < planRows.length; i++) {
            UsagePlan p = QuotaAlerts.plan(this, UsagePlan.KEYS[i]);
            ui.rowLabel(planRows[i], i % 2 == 0 ? "5 小時額度" : "每週額度", p.enabled ? p.description() : "已停用");
        }
    }
    private void providerCard(LinearLayout card, String name, QuotaData.Provider provider, long at, boolean demo, String error, int color, Runnable connect) {
        card.removeAllViews();
        TextView heading = ui.text(card, name, 22, true, 0); heading.setTextColor(getColor(color));
        if (provider == null) {
            boolean connected = name.equals("Claude") ? store.claudeLinked() || store.mode().equals("remote") : store.automatic();
            if (connected) {
                ui.text(card, "等待額度資料", 18, true, 20);
                ui.text(card, error.isEmpty() ? "已設定來源，更新成功後會在這裡顯示。" : error, 14, false, 8);
                ui.button(card, "重新讀取", false, () -> RefreshJob.request(this)); return;
            }
            ui.text(card, "尚未連接", 18, true, 20);
            ui.text(card, name.equals("Claude") ? "連接額度檔案後，這裡會顯示剩餘用量。" : "在手機登入後，即可直接更新額度。", 14, false, 8);
            ui.button(card, "連接 " + name, false, connect); return;
        }
        ui.text(card, provider.plan, 13, false, 4);
        for (QuotaData.Window w : provider.windows) {
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this); label.setText(w.label); label.setTextSize(16); label.setTextColor(getColor(R.color.primary_text));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = new TextView(this); value.setText(QuotaAlerts.number(w.remaining) + "%"); value.setTextSize(26); value.setTextColor(getColor(R.color.primary_text));
            row.addView(value, new LinearLayout.LayoutParams(-2, -2)); ui.add(card, row, 20);
            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000); bar.setProgress((int) Math.round(w.remaining * 10));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(getColor(color)));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.track)));
            bar.setContentDescription(w.label + "剩餘 " + QuotaAlerts.number(w.remaining) + "%");
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(6)); bp.topMargin = dp(8); card.addView(bar, bp);
            ui.text(card, QuotaMath.resetText(w.resetsAt, System.currentTimeMillis()), 13, false, 8);
        }
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(at));
        TextView status = ui.text(card, demo ? "示範資料" : "更新於 " + timestamp + (QuotaMath.stale(at, System.currentTimeMillis()) ? " · 資料較舊" : ""), 13, false, 18);
        if (!error.isEmpty()) { status.setText(status.getText() + "\n" + error); status.setTextColor(getColor(R.color.error)); }
    }
    @Override protected void onStart() { super.onStart(); store.prefs.registerOnSharedPreferenceChangeListener(listener); RefreshJob.schedule(this); handler.post(tick); }
    @Override protected void onStop() { store.prefs.unregisterOnSharedPreferenceChangeListener(listener); handler.removeCallbacksAndMessages(null); super.onStop(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == LINK && result == RESULT_OK && data != null && data.getData() != null) { linkClaude(data.getData()); return; }
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        long revision = store.revision();
        feedback.setText("正在讀取額度檔案…");
        io.submit(() -> {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in == null) throw new IllegalArgumentException();
                QuotaData imported = new QuotaData(RefreshJob.readLimited(in));
                runOnUiThread(() -> {
                    if (isDestroyed() || store.revision() != revision) return;
                    if (imported.claude != null && imported.codex == null && !imported.demo) {
                        store.importClaude(imported); sourceChanged();
                        feedback.setText("已更新 Claude 額度。其他資料來源維持不變。");
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("使用這份額度快照？")
                        .setMessage("匯入後會替換目前的資料與連線設定。此檔案" + (imported.demo ? "含有示範資料。" : "不會自動更新。"))
                        .setNegativeButton("取消", null).setPositiveButton("匯入", (dialog, which) -> {
                            RefreshJob.cancel(this); store.local(imported); endpoint.setText(""); token.setText(""); sourceChanged(); feedback.setText("已匯入，桌面小工具已同步。");
                        }).show();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) feedback.setText("匯入失敗：請選擇有效的額度 JSON 檔（最多 64 KB）。原本資料已保留。"); }); }
        });
    }
    private EditText field(LinearLayout parent, String label, String hint, boolean secret, int id) {
        TextView labelView = addText(parent, label, 14, R.color.primary_text, 16);
        EditText field = new EditText(this); field.setId(id); labelView.setLabelFor(id);
        field.setTextSize(16); field.setSingleLine(true); field.setMinHeight(dp(56));
        field.setTextColor(getColor(R.color.primary_text)); field.setHintTextColor(getColor(R.color.secondary_text));
        field.setHint(hint); field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        field.setBackground(surface());
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (secret) field.setSaveEnabled(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); parent.addView(field, lp);
        return field;
    }
    private TextView addText(LinearLayout parent, String value, int size, int color, int top) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(getColor(color));
        text.setLineSpacing(dp(3), 1); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top); parent.addView(text, lp); return text;
    }
    private void toggleLive() {
        if (LiveSyncService.active(this)) { stopService(new Intent(this, LiveSyncService.class)); render(); return; }
        if (!store.refreshable()) { feedback.setText("請先登入 Codex、連接 Claude 檔案，或設定自動同步來源。"); return; }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 30); return;
        }
        startLive();
    }
    private void startLive() {
        try { startForegroundService(new Intent(this, LiveSyncService.class)); feedback.setText("正在啟動每分鐘更新。可從常駐通知停止。"); }
        catch (Exception e) { feedback.setText("Android 暫時不允許啟動持續同步；仍可手動更新。"); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 30 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startLive();
        else if (request == 30) feedback.setText("需要通知權限才能顯示持續同步狀態。目前仍可使用一般背景更新。");
    }
    private GradientDrawable surface() { GradientDrawable shape = new GradientDrawable(); shape.setColor(getColor(R.color.surface)); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), getColor(R.color.border)); return shape; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
