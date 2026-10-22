import com.gather.quotapocket.QuotaData;
import org.json.JSONObject;
import org.json.JSONArray;
import java.time.Instant;

public final class QuotaDataTest {
    private static int count;
    private static void check(boolean pass) { count++; if (!pass) throw new AssertionError("Check " + count); }
    private static JSONObject fixture() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("demo", false).put("generatedAt", Instant.now().toString())
            .put("providers", new JSONObject().put("codex", new JSONObject().put("plan", "Pro").put("windows", new JSONArray()
                .put(new JSONObject().put("label", "每週").put("remainingPercent", 0).put("resetsAt", JSONObject.NULL)))));
    }
    private static void rejected(JSONObject json) throws Exception {
        boolean failed = false;
        try { new QuotaData(json.toString()); } catch (Exception e) { failed = true; }
        check(failed);
    }
    private static JSONObject window(JSONObject o) throws Exception { return o.getJSONObject("providers").getJSONObject("codex").getJSONArray("windows").getJSONObject(0); }
    public static void main(String[] args) throws Exception {
        QuotaData data = new QuotaData(fixture().toString());
        check(data.codex.windows.get(0).remaining == 0);
        check(data.codex.windows.get(0).resetsAt == 0);
        check(data.claude == null);
        check(!data.demo);
        check(QuotaData.demo().demo);
        check(QuotaData.demo().claude.windows.size() == 2);
        JSONObject o = fixture(); window(o).put("remainingPercent", 100); check(new QuotaData(o.toString()).codex.windows.get(0).remaining == 100);
        o = fixture(); window(o).put("remainingPercent", -1); rejected(o);
        o = fixture(); window(o).put("remainingPercent", 101); rejected(o);
        o = fixture(); window(o).put("remainingPercent", "41"); rejected(o);
        o = fixture(); window(o).put("remainingPercent", JSONObject.NULL); rejected(o);
        o = fixture(); window(o).put("label", ""); rejected(o);
        o = fixture(); window(o).put("resetsAt", "2026-10-20"); rejected(o);
        o = fixture(); o.put("generatedAt", Instant.now().plusSeconds(3600).toString()); rejected(o);
        o = fixture(); o.put("generatedAt", "invalid"); rejected(o);
        o = fixture(); o.put("schemaVersion", 2); rejected(o);
        o = fixture(); o.put("demo", "true"); rejected(o);
        o = fixture(); o.put("providers", new JSONObject()); rejected(o);
        o = fixture(); o.getJSONObject("providers").getJSONObject("codex").put("windows", new JSONArray()); rejected(o);
        o = fixture(); o.put("padding", "x".repeat(QuotaData.MAX_BYTES)); rejected(o);
        JSONObject claudeOnly = new JSONObject().put("schemaVersion", 1).put("generatedAt", Instant.now().minusSeconds(60).toString())
            .put("providers", new JSONObject().put("claude", new JSONObject().put("plan", "Max").put("windows", new JSONArray()
                .put(new JSONObject().put("label", "5 小時").put("remainingPercent", 62).put("resetsAt", JSONObject.NULL)))));
        QuotaData merged = QuotaData.withClaude(data, new QuotaData(claudeOnly.toString()));
        check(merged.claude.windows.get(0).remaining == 62);
        check(merged.codex.windows.get(0).remaining == 0);
        check(merged.generatedAt == data.generatedAt);
        System.out.println(count + " quota parser assertions passed.");
    }
}
