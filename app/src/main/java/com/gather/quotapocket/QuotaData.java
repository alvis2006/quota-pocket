package com.gather.quotapocket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class QuotaData {
    public static final int MAX_BYTES = 65_536;
    public final long generatedAt;
    public final boolean demo;
    public final Provider claude, codex;
    public final String json;

    public static final class Window {
        public final String label;
        public final double remaining;
        public final long resetsAt;
        Window(JSONObject o) throws JSONException {
            label = text(o, "label", 20);
            Object value = o.get("remainingPercent");
            if (!(value instanceof Number)) throw new JSONException("remainingPercent 必須是數字");
            remaining = ((Number) value).doubleValue();
            QuotaMath.remaining(remaining); // validates 0, 100, NaN and infinity without changing the value
            resetsAt = o.isNull("resetsAt") ? 0 : timestamp(o.getString("resetsAt"));
        }
    }
    public static final class Provider {
        public final String plan;
        public final List<Window> windows = new ArrayList<>();
        Provider(JSONObject o) throws JSONException {
            plan = text(o, "plan", 32);
            JSONArray list = o.getJSONArray("windows");
            if (list.length() < 1 || list.length() > 2) throw new JSONException("每個服務需有 1–2 個額度時段");
            for (int i = 0; i < list.length(); i++) windows.add(new Window(list.getJSONObject(i)));
        }
    }
    public QuotaData(String raw) throws JSONException {
        if (raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("資料過大");
        JSONObject o = new JSONObject(raw);
        if (o.getInt("schemaVersion") != 1) throw new JSONException("不支援的資料版本");
        generatedAt = timestamp(o.getString("generatedAt"));
        if (generatedAt > System.currentTimeMillis() + 300_000) throw new JSONException("資料時間超出目前時間");
        if (o.has("demo") && !(o.get("demo") instanceof Boolean)) throw new JSONException("demo 必須是布林值");
        demo = o.optBoolean("demo", false);
        JSONObject providers = o.getJSONObject("providers");
        claude = providers.isNull("claude") ? null : new Provider(providers.getJSONObject("claude"));
        codex = providers.isNull("codex") ? null : new Provider(providers.getJSONObject("codex"));
        if (claude == null && codex == null) throw new JSONException("尚無任何額度資料");
        json = o.toString();
    }
    private static String text(JSONObject o, String key, int max) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof String)) throw new JSONException(key + " 必須是文字");
        String s = ((String) v).trim();
        if (s.isEmpty() || s.length() > max || s.chars().anyMatch(Character::isISOControl)) throw new JSONException(key + " 格式不正確");
        return s;
    }
    private static long timestamp(String text) throws JSONException {
        try {
            long value = Instant.parse(text).toEpochMilli();
            if (value <= 0) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) { throw new JSONException("時間需使用 ISO 8601 格式，含時區"); }
    }
    /** Copies {@code claude}'s provider into {@code base}, keeping the base source's time and flags. */
    public static QuotaData withClaude(QuotaData base, QuotaData claude) throws JSONException {
        JSONObject root = new JSONObject(base.json);
        root.getJSONObject("providers").put("claude", new JSONObject(claude.json).getJSONObject("providers").getJSONObject("claude"));
        return new QuotaData(root.toString());
    }
    public static QuotaData demo() {
        try {
            Instant now = Instant.now();
            JSONObject root = new JSONObject().put("schemaVersion", 1).put("demo", true).put("generatedAt", now.toString());
            JSONObject providers = new JSONObject();
            providers.put("claude", new JSONObject().put("plan", "Max · 示範").put("windows", new JSONArray()
                .put(window("5 小時", 62, now.plusSeconds(2 * 3600 + 14 * 60)))
                .put(window("每週", 81, now.plusSeconds(3 * 86400 + 5 * 3600)))));
            providers.put("codex", new JSONObject().put("plan", "Plus · 示範").put("windows", new JSONArray()
                .put(window("每週", 47, now.plusSeconds(5 * 86400 + 2 * 3600)))
                .put(window("5 小時", 90, now.plusSeconds(4 * 3600 + 2 * 60)))));
            return new QuotaData(root.put("providers", providers).toString());
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static JSONObject window(String label, int percent, Instant reset) throws JSONException {
        return new JSONObject().put("label", label).put("remainingPercent", percent).put("resetsAt", reset.toString());
    }
}
