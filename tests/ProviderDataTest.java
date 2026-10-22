import com.gather.quotapocket.ProviderData;
import com.gather.quotapocket.QuotaData;
import java.time.Instant;
import org.json.JSONObject;

public final class ProviderDataTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    static JSONObject payload(double used, long duration) throws Exception {
        return new JSONObject().put("plan_type", "plus").put("rate_limit", new JSONObject().put("primary_window", new JSONObject()
            .put("used_percent", used).put("limit_window_seconds", duration).put("reset_at", 1900000000)).put("secondary_window", JSONObject.NULL));
    }
    static void rejected(JSONObject payload) throws Exception {
        boolean failed = false;
        try { ProviderData.codex(payload, System.currentTimeMillis()); } catch (Exception e) { failed = true; }
        check(failed);
    }
    public static void main(String[] args) throws Exception {
        long now = System.currentTimeMillis();
        QuotaData d = ProviderData.codex(payload(59, 604800), now);
        check(d.codex.windows.get(0).remaining == 41);
        check(d.codex.windows.get(0).label.equals("每週"));
        check(d.codex.windows.get(0).resetsAt == 1900000000000L);
        check(d.generatedAt == now);
        check(d.claude == null && !d.demo);
        check(ProviderData.codex(payload(100, 18000), now).codex.windows.get(0).remaining == 0);
        check(ProviderData.codex(payload(0, 18000), now).codex.windows.get(0).remaining == 100);
        check(ProviderData.codex(payload(0, 18000), now).codex.windows.get(0).label.equals("5 小時"));
        check(ProviderData.codex(payload(0, 900), now).codex.windows.get(0).label.equals("15 分鐘"));
        JSONObject p = payload(0, 900); p.getJSONObject("rate_limit").getJSONObject("primary_window").put("reset_at", JSONObject.NULL);
        check(ProviderData.codex(p, now).codex.windows.get(0).resetsAt == 0);
        p = payload(59, 604800); p.getJSONObject("rate_limit").put("secondary_window", payload(12, 18000).getJSONObject("rate_limit").getJSONObject("primary_window"));
        check(ProviderData.codex(p, now).codex.windows.size() == 2);
        rejected(payload(-1, 18000)); rejected(payload(101, 18000)); rejected(payload(1, 0));
        p = payload(1, 18000); p.getJSONObject("rate_limit").getJSONObject("primary_window").put("used_percent", "1"); rejected(p);
        rejected(new JSONObject().put("rate_limit", JSONObject.NULL));
        p = payload(1, 18000); p.getJSONObject("rate_limit").put("primary_window", JSONObject.NULL); rejected(p);
        System.out.println(checks + " direct provider adapter assertions passed.");
    }
}
