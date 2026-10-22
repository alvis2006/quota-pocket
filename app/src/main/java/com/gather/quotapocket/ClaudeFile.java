package com.gather.quotapocket;

import android.content.Context;
import android.net.Uri;
import java.io.FileNotFoundException;
import java.io.InputStream;

/** Re-reads the Claude snapshot the user linked (e.g. in Google Drive) through its document provider. */
final class ClaudeFile {
    private static final long MIN_INTERVAL = 60_000;
    private ClaudeFile() {}

    /** Never throws; problems are kept in "claude_error" for the widget. Returns true when new data was saved. */
    static boolean refresh(Context context, boolean force) {
        QuotaStore store = new QuotaStore(context);
        String uri = store.claudeUri();
        if (uri.isEmpty()) return false;
        long now = System.currentTimeMillis();
        if (!force && now - store.prefs.getLong("claude_checked", 0) < MIN_INTERVAL) return false;
        store.prefs.edit().putLong("claude_checked", now).apply();
        String error;
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) throw new FileNotFoundException();
            QuotaData data = new QuotaData(RefreshJob.readLimited(in));
            if (data.claude == null || data.codex != null || data.demo) {
                error = "Claude 檔案內容不正確";
            } else {
                QuotaData old = store.claudeSnapshot();
                boolean newer = old == null || data.generatedAt > old.generatedAt;
                synchronized (QuotaStore.class) {
                    if (!uri.equals(store.claudeUri())) return false; // Unlinked or replaced while reading.
                    if (newer) store.importClaude(data);
                    store.prefs.edit().remove("claude_error").apply();
                }
                return newer;
            }
        } catch (SecurityException e) {
            error = "無法再讀取 Claude 檔案，請重新選擇";
        } catch (FileNotFoundException e) {
            error = "找不到 Claude 檔案，請重新選擇";
        } catch (org.json.JSONException | IllegalArgumentException e) {
            error = "Claude 檔案內容不正確";
        } catch (Exception e) {
            error = "Claude 檔案暫時無法讀取";
        }
        synchronized (QuotaStore.class) {
            if (uri.equals(store.claudeUri())) store.prefs.edit().putString("claude_error", error).apply();
        }
        return false;
    }
}
