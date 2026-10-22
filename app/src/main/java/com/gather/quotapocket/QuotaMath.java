package com.gather.quotapocket;

/** Platform-independent quota and time semantics. */
public final class QuotaMath {
    private QuotaMath() {}

    public static double remaining(double used) {
        if (!Double.isFinite(used) || used < 0 || used > 100) throw new IllegalArgumentException("額度需介於 0–100");
        return 100 - used;
    }

    public static String resetText(long resetsAt, long now) {
        if (resetsAt == 0) return "未提供重置時間";
        long delta = resetsAt - now;
        if (delta <= 0) return "重置時間已到 · 待更新";
        long minutes = (delta + 59_999) / 60_000;
        if (minutes < 60) return minutes + " 分後重置";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 時 " + (minutes % 60) + " 分後重置";
        return hours / 24 + " 天 " + hours % 24 + " 時後重置";
    }

    /** Countdown for the ring widget: "2時14分" when narrow, "2時14分 後重置" when wide. */
    public static String ringReset(long resetsAt, long now, boolean wide) {
        if (resetsAt == 0) return "時間未知";
        long delta = resetsAt - now;
        if (delta <= 0) return "待更新";
        long minutes = (delta + 59_999) / 60_000;
        String text;
        if (minutes < 60) text = minutes + "分";
        else if (minutes < 1440) text = minutes / 60 + "時" + String.format(java.util.Locale.ROOT, "%02d", minutes % 60) + "分";
        else { long hours = minutes / 60; text = hours / 24 + "天" + (wide && hours % 24 > 0 ? hours % 24 + "時" : ""); }
        return wide ? text + " 後重置" : text;
    }

    /** Weekly windows go on the inner ring; anything shorter goes outside. */
    public static boolean weekly(String label) {
        String l = label.toLowerCase(java.util.Locale.ROOT);
        return l.contains("週") || l.contains("周") || l.contains("天") || l.contains("week") || l.contains("7d");
    }

    public static boolean stale(long generatedAt, long now) { return now - generatedAt > 3_600_000; }
}
