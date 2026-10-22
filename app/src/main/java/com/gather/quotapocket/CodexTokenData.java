package com.gather.quotapocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

/** Parse routing/expiry hints only from the fixed TLS token endpoint, never imported JWTs. */
public final class CodexTokenData {
    private CodexTokenData() {}
    public static JSONObject normalize(JSONObject response, JSONObject previous, long now) throws Exception {
        String access = response.getString("access_token");
        if (!safeHeader(access)) throw new IOException("登入回應不正確");
        JSONObject claims = claims(access);
        JSONObject id = claims(response.optString("id_token", ""));
        JSONObject auth = id.optJSONObject("https://api.openai.com/auth");
        if (auth == null) auth = claims.optJSONObject("https://api.openai.com/auth");
        String account = auth == null ? "" : auth.optString("chatgpt_account_id", "");
        if (account.isEmpty() && previous != null) account = previous.optString("account_id", "");
        if (!safeHeader(account)) throw new IOException("未取得 Codex 帳號識別碼");
        if (previous != null && !previous.getString("account_id").equals(account)) throw new IOException("續期帳號不符，請重新登入");
        long expiresAt = Math.multiplyExact(claims.optLong("exp", 0), 1000L);
        if (expiresAt == 0) expiresAt = now + Math.min(3600, response.optLong("expires_in", 3600)) * 1000L;
        if (expiresAt <= now) throw new IOException("登入已過期，請重新登入");
        String refresh = response.optString("refresh_token", previous == null ? "" : previous.optString("refresh_token", ""));
        if (refresh.isEmpty()) throw new IOException("未取得可續期的登入授權，請重新登入");
        return new JSONObject().put("access_token", access).put("account_id", account).put("expiresAt", expiresAt).put("refresh_token", refresh);
    }
    private static boolean safeHeader(String value) { return !value.isEmpty() && value.chars().allMatch(c -> c >= 33 && c <= 126); }
    private static JSONObject claims(String jwt) {
        // This decoding grants no authorization: OpenAI validates the actual bearer token.
        try { return new JSONObject(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONObject(); }
    }
}
