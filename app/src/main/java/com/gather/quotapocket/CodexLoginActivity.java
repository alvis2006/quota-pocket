package com.gather.quotapocket;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class CodexLoginActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Button> buttons = new ArrayList<>();
    private CodexAccount account;
    private TextView code, status;
    private JSONObject pending;
    private boolean busy;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        account = new CodexAccount(this);
        AppUi ui = new AppUi(this);
        LinearLayout page = ui.page(); ScrollView scroll = ui.scroll(page); ui.insets(scroll); setContentView(scroll);
        ui.back(page, this::cancelLogin);
        ui.title(page, "連接 Codex", "手機獨立更新 · 實驗功能");
        ui.text(page, "在官方網頁授權，登入憑證加密保存在手機。", 14, false, 12);
        LinearLayout first = ui.card(page), second, third;
        LinearLayout body = first;
        text(body, "1  取得登入代碼", 18);
        code = text(body, "尚未產生代碼", 28); code.setTextColor(getColor(R.color.mint));
        button(body, "產生／重新產生代碼", () -> work(() -> { pending = account.start(); runOnUiThread(() -> code.setText(pending.optString("user_code"))); return "代碼有效 15 分鐘。接著開啟官方授權頁。"; }));
        second = ui.card(page); body = second;
        text(body, "2  前往官方網頁授權", 18);
        button(body, "複製代碼並開啟官方授權頁", () -> {
            try {
                pending = account.pending();
                if (pending == null) { status.setText("請先產生新的登入代碼。"); return; }
                ClipData clip = ClipData.newPlainText("Codex 裝置登入代碼", pending.getString("user_code"));
                PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
                getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CodexAccount.VERIFY_URL)));
            } catch (Exception e) { status.setText("無法開啟瀏覽器。請確認手機已安裝瀏覽器。"); }
        });
        third = ui.card(page); body = third;
        text(body, "3  回到 App 完成連接", 18);
        button(body, "我已完成授權，連接帳號", () -> work(() -> {
            if (!account.complete()) return "尚未收到授權。請先在官方網頁完成登入、輸入代碼並確認，再回來重試。";
            runOnUiThread(() -> { RefreshJob.request(this); QuotaWidget.renderAll(this); setResult(RESULT_OK); finish(); });
            return "已登入，正在讀取額度。";
        }));
        status = text(page, "", 14); status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        ui.row(page, "登入遇到問題？", "查看裝置授權說明", () -> new android.app.AlertDialog.Builder(this).setTitle("Codex 裝置授權")
            .setMessage("如果官方網頁要求開啟裝置代碼登入，請到 ChatGPT 設定 → 安全性啟用，再重新開始。\n\n額度口袋是非官方工具，官方授權頁可能顯示 Codex。此實驗整合的可用性可能隨服務調整。")
            .setPositiveButton("知道了", null).show());
        try { pending = account.pending(); if (pending != null) code.setText(pending.getString("user_code")); }
        catch (Exception e) { status.setText("先前的登入程序已失效，請重新產生代碼。"); }
    }
    private interface Action { String run() throws Exception; }
    private void work(Action action) {
        if (busy) return;
        busy = true; for (Button b : buttons) b.setEnabled(false); status.setText("正在與 OpenAI 連線…");
        io.submit(() -> {
            String message;
            try { message = action.run(); }
            catch (Exception e) { message = e.getClass() == java.io.IOException.class ? e.getMessage() : "連線未完成。請檢查網路，或重新產生登入代碼。"; }
            String result = message;
            runOnUiThread(() -> { if (isDestroyed()) return; busy = false; for (Button b : buttons) b.setEnabled(true); status.setText(result); });
        });
    }
    private void cancelLogin() {
        synchronized (QuotaStore.class) { QuotaStore s = new QuotaStore(this); account.clearPending(); s.prefs.edit().putLong("revision", s.revision() + 1).apply(); }
        finish();
    }
    @Override public void onBackPressed() { cancelLogin(); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
    private TextView text(LinearLayout body, String content, int size) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size); view.setTextColor(getColor(R.color.primary_text)); view.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(16); body.addView(view, lp); return view;
    }
    private void button(LinearLayout body, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setTextSize(15); button.setAllCaps(false); button.setMinHeight(dp(52)); button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setTextColor(getColor(R.color.mint)); button.setBackground(getDrawable(R.drawable.button_background)); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12); body.addView(button, lp); buttons.add(button);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
