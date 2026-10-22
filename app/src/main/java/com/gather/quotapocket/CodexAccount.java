package com.gather.quotapocket;

import android.content.Context;
import java.io.IOException;
import java.net.URLEncoder;
import org.json.JSONObject;

/** Device authorization protocol from OpenAI's public Codex source, not a registered Quota Pocket OAuth client. */
final class CodexAccount {
    static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String VERIFY_URL = "https://auth.openai.com/codex/device";
    private final QuotaStore store;
    CodexAccount(Context c) { store = new QuotaStore(c); }

    JSONObject pending() throws Exception {
        String encrypted = store.prefs.getString("codex_pending", "");
        if (encrypted.isEmpty()) return null;
        JSONObject p = new JSONObject(SecretStore.decrypt(encrypted));
        if (System.currentTimeMillis() >= p.getLong("expiresAt")) { clearPending(); return null; }
        return p;
    }
    void clearPending() { store.prefs.edit().remove("codex_pending").remove("codex_poll_after").apply(); }

    JSONObject start() throws Exception {
        long revision = store.revision();
        ProviderHttp.Response response = ProviderHttp.post("/api/accounts/deviceauth/usercode", new JSONObject().put("client_id", CLIENT_ID));
        if (response.status != 200) throw new IOException(response.status == 429 ? "登入請求太頻繁，請稍後重試" : "無法啟動裝置登入（" + response.status + "）。請確認帳號已啟用裝置代碼登入。");
        JSONObject p = response.json;
        String code = p.optString("user_code", p.optString("usercode", ""));
        if (code.isEmpty() || p.optString("device_auth_id").isEmpty()) throw new IOException("登入服務回傳格式已改變");
        p.put("user_code", code).put("expiresAt", System.currentTimeMillis() + 15 * 60_000)
            .put("interval", Math.max(5, Math.min(60, p.optInt("interval", 5))));
        String encrypted = SecretStore.encrypt(p.toString());
        synchronized (QuotaStore.class) {
            if (revision != store.revision()) throw new IOException("設定已變更，請重新登入");
            store.prefs.edit().putString("codex_pending", encrypted).remove("codex_poll_after").apply();
        }
        return p;
    }
    boolean complete() throws Exception {
        JSONObject p = pending();
        if (p == null) throw new IOException("登入代碼已過期，請重新開始");
        long now = System.currentTimeMillis();
        if (now < store.prefs.getLong("codex_poll_after", 0)) throw new IOException("請稍候幾秒再確認授權");
        long revision = store.revision();
        store.prefs.edit().putLong("codex_poll_after", now + p.getInt("interval") * 1000L).apply();
        ProviderHttp.Response response = ProviderHttp.post("/api/accounts/deviceauth/token", new JSONObject()
            .put("device_auth_id", p.getString("device_auth_id")).put("user_code", p.getString("user_code")));
        if (response.status == 403 || response.status == 404) return false;
        if (response.status == 429) {
            store.prefs.edit().putLong("codex_poll_after", now + response.retrySeconds * 1000L).apply();
            throw new IOException("登入服務要求稍後再試");
        }
        if (response.status != 200) throw new IOException("裝置授權失敗，請重新開始（" + response.status + "）");
        // The authorization code is single-use. Never automatically retry its token exchange.
        clearPending();
        String body = "grant_type=authorization_code&client_id=" + CLIENT_ID + "&redirect_uri=" + encode(ProviderHttp.AUTH + "/deviceauth/callback")
            + "&code=" + encode(response.json.getString("authorization_code")) + "&code_verifier=" + encode(response.json.getString("code_verifier"));
        ProviderHttp.Response tokens = ProviderHttp.call(ProviderHttp.AUTH + "/oauth/token", body, "application/x-www-form-urlencoded", null, null);
        if (tokens.status != 200) throw new IOException("無法完成登入，請重新開始");
        JSONObject credentials = CodexTokenData.normalize(tokens.json, null, System.currentTimeMillis());
        String encrypted = SecretStore.encrypt(credentials.toString());
        synchronized (QuotaStore.class) {
            if (store.revision() != revision) throw new IOException("設定已變更，請重新登入");
            store.prefs.edit().putString("codex_tokens", encrypted).putString("mode", "codex")
                .remove("data").remove("secret").remove("endpoint").remove("error").remove("loading")
                .remove("codex_retry_after").putLong("revision", revision + 1).apply();
        }
        return true;
    }

    synchronized QuotaData fetch() throws Exception {
        if (System.currentTimeMillis() < store.prefs.getLong("codex_retry_after", 0)) throw new IOException("服務要求降低更新頻率，稍後會再試");
        String saved = store.prefs.getString("codex_tokens", "");
        if (saved.isEmpty()) throw new IOException("請先在手機登入 Codex");
        JSONObject tokens = new JSONObject(SecretStore.decrypt(saved));
        boolean refreshed = false;
        if (tokens.getLong("expiresAt") < System.currentTimeMillis() + 60_000) {
            tokens = refresh(tokens, saved); refreshed = true;
            saved = store.prefs.getString("codex_tokens", "");
        }
        ProviderHttp.Response response = usage(tokens);
        if (response.status == 401 && !refreshed) { tokens = refresh(tokens, saved); response = usage(tokens); }
        if (response.status == 429) {
            store.prefs.edit().putLong("codex_retry_after", System.currentTimeMillis() + response.retrySeconds * 1000L).apply();
            throw new IOException("更新太頻繁，已暫時降低頻率");
        }
        if (response.status == 401 || response.status == 403) throw new IOException("Codex 授權失效或存取受限，請重新登入");
        if (response.status != 200) throw new IOException("Codex 暫時無法更新（" + response.status + "）");
        return ProviderData.codex(response.json, System.currentTimeMillis());
    }
    private ProviderHttp.Response usage(JSONObject t) throws Exception {
        return ProviderHttp.call(ProviderHttp.USAGE, null, null, t.getString("access_token"), t.getString("account_id"));
    }
    private JSONObject refresh(JSONObject previous, String saved) throws Exception {
        if (previous.optString("refresh_token").isEmpty()) throw new IOException("登入已過期，請重新登入 Codex");
        ProviderHttp.Response response = ProviderHttp.post("/oauth/token", new JSONObject().put("client_id", CLIENT_ID)
            .put("grant_type", "refresh_token").put("refresh_token", previous.getString("refresh_token")));
        if (response.status == 429) {
            store.prefs.edit().putLong("codex_retry_after", System.currentTimeMillis() + response.retrySeconds * 1000L).apply();
            throw new IOException("登入服務繁忙，稍後再試");
        }
        if (response.status != 200) throw new IOException("登入續期失敗，請重新登入 Codex");
        JSONObject result = CodexTokenData.normalize(response.json, previous, System.currentTimeMillis());
        String encrypted = SecretStore.encrypt(result.toString());
        synchronized (QuotaStore.class) {
            if (!saved.equals(store.prefs.getString("codex_tokens", "")) || !store.mode().equals("codex")) throw new IOException("帳號設定已變更");
            if (!store.prefs.edit().putString("codex_tokens", encrypted).commit()) throw new IOException("無法保存登入續期，請重新登入");
        }
        return result;
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
}
