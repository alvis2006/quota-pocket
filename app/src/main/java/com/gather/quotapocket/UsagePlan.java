package com.gather.quotapocket;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Percentage-point budgets, independent of Android and of the data provider. */
public final class UsagePlan {
    public static final long HOUR = 3_600_000L, WEEK = 168 * HOUR, FIVE_HOURS = 5 * HOUR;
    public static final String[] KEYS = {"claude_5h", "claude_week", "codex_5h", "codex_week"};
    public static final String[] NAMES = {"Claude · 5 小時", "Claude · 每週", "Codex · 5 小時", "Codex · 每週"};
    public boolean enabled = true, overEnabled = true, underEnabled = true;
    public String mode = "average";
    public double over = 10, under = 10, step = 10, reserve = 30, reserveHours;
    public double[] weights = {1, 1, 1, 1, 1, 1, 1};

    public UsagePlan(boolean weekly) { reserveHours = weekly ? 48 : 1; }
    public void validate(long duration) {
        if (!mode.equals("average") && !mode.equals("weighted") && !mode.equals("reserve"))
            throw new IllegalArgumentException("請選擇使用計劃");
        range(over, 1, 100); range(under, 1, 100); range(step, 1, 100); range(reserve, 1, 99);
        range(reserveHours, 0.25, duration / (double) HOUR - 0.25);
        if (weights.length != 7) throw new IllegalArgumentException("請設定一週七天的比重");
        double sum = 0;
        for (double w : weights) { range(w, 0, 100); sum += w; }
        if (sum == 0) throw new IllegalArgumentException("至少一天的分配比重需大於 0");
    }
    private static void range(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("數值需介於 " + min + "–" + max);
    }
    public String json() {
        try {
            JSONArray w = new JSONArray(); for (double v : weights) w.put(v);
            return new JSONObject().put("enabled", enabled).put("overEnabled", overEnabled)
                .put("underEnabled", underEnabled).put("mode", mode).put("over", over).put("under", under)
                .put("step", step).put("reserve", reserve).put("reserveHours", reserveHours).put("weights", w).toString();
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
    }
    public static UsagePlan parse(String raw, boolean weekly) {
        UsagePlan p = new UsagePlan(weekly);
        if (raw == null || raw.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(raw);
            p.enabled = o.getBoolean("enabled"); p.overEnabled = o.getBoolean("overEnabled");
            p.underEnabled = o.getBoolean("underEnabled"); p.mode = o.getString("mode");
            p.over = o.getDouble("over"); p.under = o.getDouble("under"); p.step = o.getDouble("step");
            p.reserve = o.getDouble("reserve"); p.reserveHours = o.getDouble("reserveHours");
            JSONArray w = o.getJSONArray("weights");
            if (w.length() != 7) throw new IllegalArgumentException();
            for (int i = 0; i < 7; i++) p.weights[i] = w.getDouble(i);
            p.validate(weekly ? WEEK : FIVE_HOURS);
            return p;
        } catch (Exception e) { return new UsagePlan(weekly); }
    }
    public String description() {
        return mode.equals("weighted") ? "按星期分配" : mode.equals("reserve") ? "保留額度" : "平均分配";
    }
    public double expected(long reset, long duration, long at, ZoneId zone) {
        validate(duration);
        long start = reset - duration, t = Math.max(start, Math.min(reset, at));
        if (mode.equals("weighted")) {
            double total = weightedTime(start, reset, zone);
            // A five-hour window may fall entirely on a zero-budget day.
            return total == 0 ? 0 : 100 * weightedTime(start, t, zone) / total;
        }
        if (mode.equals("reserve")) {
            long deadline = reset - Math.round(reserveHours * HOUR);
            if (t <= deadline) return (100 - reserve) * (t - start) / (double) (deadline - start);
            return 100 - reserve + reserve * (t - deadline) / (double) (reset - deadline);
        }
        return 100 * (t - start) / (double) duration;
    }
    private double weightedTime(long start, long end, ZoneId zone) {
        double result = 0;
        while (start < end) {
            ZonedDateTime day = Instant.ofEpochMilli(start).atZone(zone);
            long next = day.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long stop = Math.min(next, end);
            // Weight is a per-hour rate: partial days and DST use actual elapsed time.
            result += (stop - start) * weights[day.getDayOfWeek().getValue() - 1];
            start = stop;
        }
        return result;
    }
    /** Only known duration labels are eligible; never guess a custom window is five hours. */
    public static long duration(String label) {
        String s = label.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        if (s.equals("每週") || s.equals("每周") || s.equals("weekly") || s.equals("week") ||
            s.equals("7天") || s.equals("7d") || s.equals("7days")) return WEEK;
        if (s.equals("5小時") || s.equals("5小时") || s.equals("5h") || s.equals("5hours")) return FIVE_HOURS;
        return 0;
    }
}
