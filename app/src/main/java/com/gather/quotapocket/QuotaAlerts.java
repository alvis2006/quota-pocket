package com.gather.quotapocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Local notifications only; uses the existing sync cadence and never schedules speculative resets. */
final class QuotaAlerts {
    static final String CHANNEL = "quota_plan_silent_v1";
    private QuotaAlerts() {}
    static SharedPreferences settings(Context c) { return c.getSharedPreferences("usage_plans", Context.MODE_PRIVATE); }
    static SharedPreferences history(Context c) { return c.getSharedPreferences("usage_alert_history", Context.MODE_PRIVATE); }
    static UsagePlan plan(Context c, String key) { return UsagePlan.parse(settings(c).getString(key, ""), key.endsWith("week")); }
    static void channels(Context c) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "用量計劃與重置（無聲）", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("計劃偏離與來源確認的額度重置；預設不響鈴、不震動");
        channel.setSound(null, null); channel.enableVibration(false);
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean permitted(Context c) {
        channels(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    static String permissionText(Context c) {
        if (!permitted(c)) return "系統通知尚未允許，或此通知類別已關閉。計劃仍會保存。";
        NotificationChannel channel = c.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
        if (channel.getSound() != null || channel.shouldVibrate())
            return "系統已將這類提醒改為有聲或震動；可在下方系統設定恢復無聲。";
        return "通知已允許 · 無聲、不震動";
    }
    static void clearHistory(Context c) {
        synchronized (QuotaStore.class) {
            history(c).edit().clear().apply();
            for (int i = 0; i < 4; i++) c.getSystemService(NotificationManager.class).cancel(200 + i);
        }
    }
    static void savePlan(Context c, String key, UsagePlan p) {
        synchronized (QuotaStore.class) {
            if (!plan(c, key).json().equals(p.json())) {
                settings(c).edit().putString(key, p.json()).apply();
                history(c).edit().remove(key).apply();
                c.getSystemService(NotificationManager.class).cancel(200 + index(key));
            }
            evaluate(c);
        }
    }
    static int index(String key) {
        for (int i = 0; i < UsagePlan.KEYS.length; i++) if (UsagePlan.KEYS[i].equals(key)) return i;
        throw new IllegalArgumentException("未知的額度時段");
    }
    private static final class Sample {
        QuotaData.Window window;
        long at, duration;
        boolean demo;
        String source;
    }
    private static Sample sample(QuotaStore store, String key) {
        boolean claude = key.startsWith("claude"), weekly = key.endsWith("week");
        QuotaData base = null;
        try { base = new QuotaData(store.prefs.getString("data", "")); } catch (Exception ignored) {}
        boolean sidecar = claude && (base == null || (base.claude == null && !base.demo));
        QuotaData data = sidecar ? store.claudeSnapshot() : base;
        if (data == null) return null;
        QuotaData.Provider provider = claude ? data.claude : data.codex;
        if (provider == null) return null;
        long target = weekly ? UsagePlan.WEEK : UsagePlan.FIVE_HOURS;
        Sample s = null;
        for (QuotaData.Window window : provider.windows) {
            if (UsagePlan.duration(window.label) != target) continue;
            if (s != null) return null; // Ambiguous duplicate windows must not share alert history.
            s = new Sample(); s.window = window; s.at = data.generatedAt; s.duration = target; s.demo = data.demo;
            s.source = (sidecar ? "claude:" + store.claudeUri() : store.mode() + ":" + store.revision()) + ":" + provider.plan;
        }
        return s;
    }
    static String status(Context c, String key) {
        Sample s = sample(new QuotaStore(c), key);
        String unavailable = unavailable(s, System.currentTimeMillis());
        if (unavailable != null) return unavailable;
        UsagePlan p = plan(c, key);
        double expected = p.expected(s.window.resetsAt, s.duration, s.at, ZoneId.systemDefault());
        double used = 100 - s.window.remaining, delta = used - expected;
        String time = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(s.at));
        return "資料時間 " + time + "\n計劃已用 " + number(expected) + "% · 實際已用 " + number(used) + "%\n"
            + (delta >= 0 ? "超用 " : "少用 ") + number(Math.abs(delta)) + " 個百分點";
    }
    private static String unavailable(Sample s, long now) {
        if (s == null) return "等待此時段的額度資料";
        if (s.demo) return "示範資料不會觸發提醒";
        if (!AlertRules.fresh(s.at, now)) return "資料已超過 1 小時或時間不正確，暫停判斷";
        if (s.window.resetsAt <= 0) return "來源未提供重置時間，暫停判斷";
        if (s.window.resetsAt <= now) return "重置時間已到，等待來源確認";
        if (s.window.resetsAt - s.at > s.duration) return "來源週期時間不符，暫停判斷";
        return null;
    }
    /** A reminder failure must never prevent a valid quota update from being stored. */
    static void evaluate(Context c) {
        try {
            synchronized (QuotaStore.class) {
                SharedPreferences config = settings(c);
                if (!config.getBoolean("enabled", false)) return;
                QuotaStore store = new QuotaStore(c);
                SharedPreferences history = history(c);
                long now = System.currentTimeMillis();
                for (int i = 0; i < UsagePlan.KEYS.length; i++) {
                    String key = UsagePlan.KEYS[i];
                    Sample s = sample(store, key);
                    if (unavailable(s, now) != null) continue;
                    UsagePlan p = plan(c, key);
                    AlertRules.Result r = AlertRules.evaluate(p, AlertRules.State.parse(history.getString(key, "")),
                        s.source, s.window.resetsAt, s.duration, s.at, now, s.window.remaining, ZoneId.systemDefault());
                    if (r == null) continue;
                    boolean reset = r.reset && config.getBoolean("reset", true);
                    if (reset || r.direction != 0) {
                        String title = UsagePlan.NAMES[i] + (reset ? " · 額度已重置" : r.direction > 0 ? " · 超出使用計劃" : " · 低於使用計劃");
                        String message = "計劃已用 " + number(r.expected) + "%，實際已用 " + number(r.used) + "%。";
                        if (r.direction != 0) message += (r.direction > 0 ? "超用 " : "少用 ") + number(Math.abs(r.used - r.expected)) + " 個百分點。";
                        if (reset) message += "來源已確認新的額度週期。";
                        message += " 剩餘 " + number(s.window.remaining) + "%。";
                        if (!notify(c, 200 + i, title, message, key)) continue;
                    }
                    history.edit().putString(key, r.state.json()).apply();
                }
            }
        } catch (RuntimeException ignored) { /* Android may revoke permission while delivering. */ }
    }
    static boolean test(Context c) {
        return notify(c, 299, "額度口袋 · 測試提醒", "這是一則無聲測試通知，不代表真實用量，也不影響提醒紀錄。", null);
    }
    private static boolean notify(Context c, int id, String title, String message, String key) {
        if (!permitted(c)) return false;
        Intent intent = new Intent(c, AlertSettingsActivity.class);
        if (key != null) intent.putExtra("plan", key);
        PendingIntent open = PendingIntent.getActivity(c, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STATUS).build();
        try { c.getSystemService(NotificationManager.class).notify(id, n); return true; }
        catch (SecurityException e) { return false; }
    }
    static String number(double value) { return String.format(Locale.getDefault(), "%.1f", value); }
}
