package com.gather.quotapocket;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

final class QuotaStore {
    final SharedPreferences prefs;
    private final Context context;
    QuotaStore(Context c) { context = c.getApplicationContext(); prefs = c.getSharedPreferences("quota", Context.MODE_PRIVATE); }
    String mode() { return prefs.getString("mode", "empty"); }
    /** The main source updates itself (Codex login or a custom endpoint). */
    boolean automatic() { return mode().equals("remote") || mode().equals("codex"); }
    /** Something can be re-read in the background: the main source or a linked Claude file. */
    boolean refreshable() { return automatic() || claudeLinked(); }
    boolean claudeLinked() { return !claudeUri().isEmpty(); }
    String claudeUri() { return prefs.getString("claude_uri", ""); }
    void linkClaude(String uri) { prefs.edit().putString("claude_uri", uri).remove("claude_error").remove("claude_checked").apply(); }
    void unlinkClaude() { prefs.edit().remove("claude_uri").remove("claude_error").remove("claude_checked").apply(); }
    String endpoint() { return prefs.getString("endpoint", ""); }
    long revision() { return prefs.getLong("revision", 0); }
    String error() { return prefs.getString("error", ""); }
    boolean loading() { return System.currentTimeMillis() - prefs.getLong("loading", 0) < 60_000; }
    /** The active source's snapshot, with a separately imported Claude snapshot filled in when the source has none. */
    QuotaData data() {
        QuotaData base = parse(prefs.getString("data", "")), claude = claudeSnapshot();
        if (claude == null || (base != null && (base.claude != null || base.demo))) return base;
        if (base == null) return claude;
        try { return QuotaData.withClaude(base, claude); }
        catch (Exception e) { return base; }
    }
    QuotaData claudeSnapshot() { return parse(prefs.getString("claude_data", "")); }
    /** Claude-only snapshots sit beside any source, so Codex can stay signed in on the phone. */
    void importClaude(QuotaData data) {
        if (data.claude == null || data.codex != null || data.demo) throw new IllegalArgumentException("不是 Claude 額度快照");
        synchronized (QuotaStore.class) {
            SharedPreferences.Editor e = prefs.edit().putString("claude_data", data.json);
            // Demo data would hide a real Claude snapshot, so leave the demo.
            if (mode().equals("demo")) e.remove("data").putString("mode", "empty").putLong("revision", revision() + 1);
            e.apply();
        }
        QuotaAlerts.evaluate(context);
    }
    private static QuotaData parse(String json) {
        try { return new QuotaData(json); }
        catch (Exception e) { return null; }
    }
    static void validateEndpoint(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("請輸入完整 HTTPS 網址，不含帳號密碼或 # 片段");
    }
    void connect(String url, String token) throws Exception {
        validateEndpoint(url);
        if (token.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("連線密碼不能含換行");
        String encrypted = SecretStore.encrypt(token);
        synchronized (QuotaStore.class) {
            prefs.edit().putString("mode", "remote").putString("endpoint", url).putString("secret", encrypted)
                .remove("codex_tokens").remove("codex_pending").remove("codex_retry_after")
                .remove("data").remove("error").remove("loading").putLong("revision", revision() + 1).apply();
        }
    }
    void local(QuotaData data) {
        synchronized (QuotaStore.class) {
            SharedPreferences.Editor e = prefs.edit().putString("mode", data.demo ? "demo" : "local").putString("data", data.json)
                .remove("codex_tokens").remove("codex_pending").remove("codex_retry_after")
                .remove("endpoint").remove("secret").remove("error").remove("loading").putLong("revision", revision() + 1);
            if (data.claude != null) e.remove("claude_data");
            e.apply();
        }
        QuotaAlerts.evaluate(context);
    }
    void clear() {
        synchronized (QuotaStore.class) {
            long next = revision() + 1; prefs.edit().clear().putLong("revision", next).apply();
            QuotaAlerts.clearHistory(context);
        }
    }
    boolean begin(long revision) {
        synchronized (QuotaStore.class) {
            if (revision != revision() || !automatic() || loading()) return false;
            prefs.edit().putLong("loading", System.currentTimeMillis()).remove("error").apply();
            return true;
        }
    }
    void finish(long revision, QuotaData data, String error) {
        synchronized (QuotaStore.class) {
            if (revision != revision()) return; // A response from a previous source cannot overwrite a new source.
            SharedPreferences.Editor e = prefs.edit().remove("loading").putString("error", error);
            if (data != null) e.putString("data", data.json);
            e.apply();
        }
        if (data != null) QuotaAlerts.evaluate(context);
    }
}
