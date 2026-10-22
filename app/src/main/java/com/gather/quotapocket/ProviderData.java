package com.gather.quotapocket;

import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only adapter for the usage response used by the open-source Codex client. */
public final class ProviderData {
    private ProviderData() {}
    public static QuotaData codex(JSONObject payload, long now) throws JSONException {
        JSONObject limits = payload.optJSONObject("rate_limit");
        if (limits == null) throw new JSONException("此帳號未提供時段額度");
        JSONArray windows = new JSONArray();
        for (String key : new String[]{"primary_window", "secondary_window"}) {
            if (limits.isNull(key)) continue;
            JSONObject window = limits.getJSONObject(key);
            Object used = window.get("used_percent");
            if (!(used instanceof Number)) throw new JSONException("缺少使用百分比");
            double remaining = QuotaMath.remaining(((Number) used).doubleValue());
            long duration = window.getLong("limit_window_seconds");
            if (duration <= 0) throw new JSONException("缺少額度時段長度");
            String label = duration == 604800 ? "每週" : duration % 3600 == 0 ? duration / 3600 + " 小時" : (duration + 59) / 60 + " 分鐘";
            Object reset = JSONObject.NULL;
            if (!window.isNull("reset_at")) {
                long seconds = window.getLong("reset_at");
                if (seconds <= 0) throw new JSONException("無效重置時間");
                reset = Instant.ofEpochSecond(seconds).toString();
            }
            windows.put(new JSONObject().put("label", label).put("remainingPercent", remaining).put("resetsAt", reset));
        }
        String plan = payload.optString("plan_type", "Codex");
        if (plan.isEmpty() || plan.equals("null")) plan = "Codex";
        JSONObject root = new JSONObject().put("schemaVersion", 1).put("demo", false)
            .put("generatedAt", Instant.ofEpochMilli(now).toString())
            .put("providers", new JSONObject().put("codex", new JSONObject().put("plan", plan).put("windows", windows)));
        return new QuotaData(root.toString());
    }
}
