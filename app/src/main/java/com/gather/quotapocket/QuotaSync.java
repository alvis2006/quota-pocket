package com.gather.quotapocket;

import android.content.Context;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Shared by the minute service and scheduled jobs; only one fetch can run at a time. */
final class QuotaSync implements Runnable {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Context context;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile HttpsURLConnection connection;
    QuotaSync(Context context) { this.context = context.getApplicationContext(); }
    void cancel() { cancelled.set(true); HttpsURLConnection c = connection; if (c != null) c.disconnect(); }
    @Override public void run() {
        if (cancelled.get() || !BUSY.compareAndSet(false, true)) return;
        QuotaStore store = new QuotaStore(context);
        long revision;
        String mode, endpoint, encrypted;
        synchronized (QuotaStore.class) {
            revision = store.revision(); mode = store.mode(); endpoint = store.endpoint(); encrypted = store.prefs.getString("secret", "");
        }
        boolean began = false;
        QuotaData data = null;
        String error = "";
        try {
            if (ClaudeFile.refresh(context, false)) QuotaWidget.renderAll(context);
            if (cancelled.get() || !store.begin(revision)) return;
            began = true;
            QuotaWidget.renderAll(context);
            if (mode.equals("codex")) {
                try { data = new CodexAccount(context).fetch(); }
                catch (java.io.IOException e) {
                    error = e.getClass() == java.io.IOException.class ? e.getMessage() : "連線失敗，稍後會再試";
                }
            } else {
                QuotaStore.validateEndpoint(endpoint);
                String token = SecretStore.decrypt(encrypted);
                connection = (HttpsURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Cache-Control", "no-cache");
                if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                int status = connection.getResponseCode();
                if (status == 401 || status == 403) error = "連線密碼失效，請重新設定";
                else if (status != 200) error = "來源暫時無法使用（" + status + "）";
                else {
                    if (connection.getContentLengthLong() > QuotaData.MAX_BYTES) throw new IllegalArgumentException();
                    try (InputStream in = connection.getInputStream()) { data = new QuotaData(RefreshJob.readLimited(in)); }
                }
            }
        } catch (org.json.JSONException | IllegalArgumentException e) { error = "額度資料格式已變更，請稍後更新 App"; }
        catch (java.io.IOException e) { error = "連線失敗，稍後會再試"; }
        catch (Exception e) { error = "無法讀取登入設定，請重新連接"; }
        finally {
            HttpsURLConnection c = connection; if (c != null) c.disconnect(); connection = null;
            if (began) store.finish(revision, cancelled.get() ? null : data, cancelled.get() ? "同步已暫停" : error);
            BUSY.set(false); QuotaWidget.renderAll(context);
        }
    }
}
