package com.gather.quotapocket;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Fixed provider destinations only; redirects never carry account tokens elsewhere. */
final class ProviderHttp {
    static final String AUTH = "https://auth.openai.com";
    static final String USAGE = "https://chatgpt.com/backend-api/wham/usage";
    static final class Response {
        final int status;
        final JSONObject json;
        final int retrySeconds;
        Response(int status, JSONObject json, int retrySeconds) { this.status = status; this.json = json; this.retrySeconds = retrySeconds; }
    }
    static Response call(String url, String body, String contentType, String bearer, String account) throws Exception {
        if (!(url.equals(AUTH + "/api/accounts/deviceauth/usercode") || url.equals(AUTH + "/api/accounts/deviceauth/token")
            || url.equals(AUTH + "/oauth/token") || url.equals(USAGE))) throw new IOException("不支援的帳號服務");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "QuotaPocket/0.2 (Android; personal quota monitor)");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (account != null && !account.isEmpty()) connection.setRequestProperty("ChatGPT-Account-Id", account);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            int retry = 60;
            try { retry = Math.max(60, Math.min(3600, Integer.parseInt(connection.getHeaderField("Retry-After")))); } catch (Exception ignored) {}
            JSONObject data = new JSONObject();
            if (status >= 200 && status < 300) {
                try (InputStream in = connection.getInputStream()) { data = new JSONObject(RefreshJob.readLimited(in)); }
            }
            return new Response(status, data, retry);
        } finally { connection.disconnect(); }
    }
    static Response post(String path, JSONObject data) throws Exception { return call(AUTH + path, data.toString(), "application/json", null, null); }
}
