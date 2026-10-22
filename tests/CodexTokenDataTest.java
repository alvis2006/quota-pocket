import com.gather.quotapocket.CodexTokenData;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

public final class CodexTokenDataTest {
    static int checks;
    static final long NOW = 1_800_000_000_000L;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    static String jwt(String account, long exp) throws Exception {
        JSONObject body = new JSONObject().put("exp", exp).put("https://api.openai.com/auth", new JSONObject().put("chatgpt_account_id", account));
        return "test." + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8)) + ".test";
    }
    static JSONObject response(String account) throws Exception { return new JSONObject().put("access_token", jwt(account, NOW / 1000 + 3600)).put("refresh_token", "test-refresh-1"); }
    static void rejects(JSONObject data, JSONObject previous) throws Exception {
        boolean failed = false; try { CodexTokenData.normalize(data, previous, NOW); } catch (Exception e) { failed = true; } check(failed);
    }
    public static void main(String[] args) throws Exception {
        JSONObject first = CodexTokenData.normalize(response("test-account"), null, NOW);
        check(first.getString("account_id").equals("test-account"));
        check(first.getLong("expiresAt") == NOW + 3600000);
        check(first.getString("refresh_token").equals("test-refresh-1"));
        JSONObject next = response("test-account").put("refresh_token", "rotated-test-refresh");
        check(CodexTokenData.normalize(next, first, NOW).getString("refresh_token").equals("rotated-test-refresh"));
        next.remove("refresh_token");
        check(CodexTokenData.normalize(next, first, NOW).getString("refresh_token").equals("test-refresh-1"));
        rejects(response("different-account"), first);
        rejects(response("test-account").put("access_token", "bad\r\nheader"), null);
        rejects(response("test-account").put("access_token", jwt("test-account", NOW / 1000 - 1)), null);
        rejects(response(""), null);
        rejects(response("bad\naccount"), null);
        JSONObject missingRefresh = response("test-account"); missingRefresh.remove("refresh_token"); rejects(missingRefresh, null);
        rejects(new JSONObject().put("access_token", "opaque-token").put("refresh_token", "test"), null);
        JSONObject idFallback = new JSONObject().put("access_token", "opaque-token").put("id_token", jwt("test-account", NOW / 1000 + 3600)).put("expires_in", 120).put("refresh_token", "test");
        check(CodexTokenData.normalize(idFallback, null, NOW).getLong("expiresAt") == NOW + 120000);
        System.out.println(checks + " token lifecycle assertions passed.");
    }
}
