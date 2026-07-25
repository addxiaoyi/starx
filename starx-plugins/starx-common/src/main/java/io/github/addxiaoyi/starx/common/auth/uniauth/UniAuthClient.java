/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth.uniauth;

import io.github.addxiaoyi.starx.common.auth.uniauth.RSAUtil;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class UniAuthClient {
    private static final Logger logger = Logger.getLogger(UniAuthClient.class.getName());
    private static final Gson gson = new Gson();
    private final UniAuthConfig config;
    private final HttpClient httpClient;
    private String publicKey;

    public UniAuthClient(UniAuthConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMs())).build();
    }

    private String getPublicKey() {
        if (this.publicKey == null) {
            this.refreshPublicKey();
        }
        return this.publicKey;
    }

    private void refreshPublicKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(UniAuthClient.normalizeUrl(this.config.apiUrl()) + "publickey")).timeout(Duration.ofMillis(this.config.timeoutMs())).GET().build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch public key: HTTP " + response.statusCode());
            }
            this.publicKey = response.body().trim();
            logger.log(Level.INFO, "UniAuth public key fetched");
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to fetch UniAuth public key", e);
        }
    }

    private JsonObject request(String endpoint, Map<String, String> data) {
        try {
            String decrypted;
            String hash;
            HashMap<String, Object> payload = new HashMap<String, Object>();
            payload.put("data", data);
            payload.put("apikey", this.config.apiKey());
            payload.put("timestamp", System.currentTimeMillis());
            String encrypted = RSAUtil.encryptByPublicKey(gson.toJson(payload), this.getPublicKey());
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(UniAuthClient.normalizeUrl(this.config.apiUrl()) + endpoint)).timeout(Duration.ofMillis(this.config.timeoutMs())).POST(HttpRequest.BodyPublishers.ofString(encrypted)).build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            String checksum = response.headers().firstValue("X-Checksum").orElse("");
            String timestamp = response.headers().firstValue("X-Timestamp").orElse("");
            if (!(checksum.isEmpty() || timestamp.isEmpty() || (hash = UniAuthClient.sha256(body) + "$" + timestamp).equals(decrypted = RSAUtil.decryptByPublicKey(checksum, this.getPublicKey())))) {
                throw new RuntimeException("Response checksum mismatch");
            }
            return JsonParser.parseString(body).getAsJsonObject();
        }
        catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("checksum")) {
                throw new RuntimeException(e);
            }
            try {
                String decrypted;
                String hash;
                this.refreshPublicKey();
                HashMap<String, Object> payload = new HashMap<String, Object>();
                payload.put("data", data);
                payload.put("apikey", this.config.apiKey());
                payload.put("timestamp", System.currentTimeMillis());
                String encrypted = RSAUtil.encryptByPublicKey(gson.toJson(payload), this.getPublicKey());
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(UniAuthClient.normalizeUrl(this.config.apiUrl()) + endpoint)).timeout(Duration.ofMillis(this.config.timeoutMs())).POST(HttpRequest.BodyPublishers.ofString(encrypted)).build();
                HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                String checksum = response.headers().firstValue("X-Checksum").orElse("");
                String timestamp = response.headers().firstValue("X-Timestamp").orElse("");
                if (!(checksum.isEmpty() || timestamp.isEmpty() || (hash = UniAuthClient.sha256(body) + "$" + timestamp).equals(decrypted = RSAUtil.decryptByPublicKey(checksum, this.getPublicKey())))) {
                    throw new RuntimeException("Response checksum mismatch");
                }
                return JsonParser.parseString(body).getAsJsonObject();
            }
            catch (Exception ex) {
                throw new RuntimeException("UniAuth request failed: " + endpoint, ex);
            }
        }
    }

    public CompletableFuture<LoginResponse> login(String username, String password) {
        CompletableFuture<LoginResponse> future = new CompletableFuture<LoginResponse>();
        try {
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("username", username);
            data.put("password", password);
            JsonObject json = this.request("login", data);
            int code = json.has("code") ? json.get("code").getAsInt() : 500;
            switch (code) {
                case 200: {
                    future.complete(new LoginResponse(true, "\u767b\u5f55\u6210\u529f", null, null));
                    break;
                }
                case 401: {
                    future.complete(new LoginResponse(false, "\u5bc6\u7801\u9519\u8bef", null, null));
                    break;
                }
                case 402: {
                    future.complete(new LoginResponse(false, "\u7528\u6237\u672a\u6ce8\u518c", null, null));
                    break;
                }
                case 403: {
                    future.complete(new LoginResponse(false, "\u90ae\u7bb1\u672a\u9a8c\u8bc1", null, null));
                    break;
                }
                default: {
                    future.complete(new LoginResponse(false, "\u8ba4\u8bc1\u5931\u8d25: " + code, null, null));
                    break;
                }
            }
        }
        catch (Exception e) {
            logger.log(Level.WARNING, "UniAuth login failed", e);
            future.complete(new LoginResponse(false, e.getMessage(), null, null));
        }
        return future;
    }

    public CompletableFuture<StatusResponse> fetchStatus(String username) {
        CompletableFuture<StatusResponse> future = new CompletableFuture<StatusResponse>();
        try {
            int code;
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("username", username);
            JsonObject json = this.request("playerInfo", data);
            int n = code = json.has("code") ? json.get("code").getAsInt() : 500;
            if (code == 200) {
                boolean registered = false;
                boolean exists = false;
                JsonObject profile = json.has("data") ? json.get("data").getAsJsonObject() : new JsonObject();
                if (profile.has("profile.exists")) {
                    exists = profile.get("profile.exists").getAsBoolean();
                } else if (profile.has("profile")) {
                    exists = profile.get("profile").getAsJsonObject().get("exists").getAsBoolean();
                }
                if (profile.has("profile.registered")) {
                    registered = profile.get("profile.registered").getAsBoolean();
                } else if (profile.has("profile")) {
                    registered = profile.get("profile").getAsJsonObject().get("registered").getAsBoolean();
                }
                future.complete(new StatusResponse(exists, registered, registered ? "REGISTERED" : (exists ? "IMPORTED" : "NOT_EXIST")));
            } else {
                future.complete(new StatusResponse(false, false, "ERROR"));
            }
        }
        catch (Exception e) {
            logger.log(Level.WARNING, "UniAuth status request failed", e);
            future.complete(new StatusResponse(false, false, "ERROR"));
        }
        return future;
    }

    private static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url : url + "/";
    }

    private static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record LoginResponse(boolean success, String message, String userId, String email) {
    }

    public record StatusResponse(boolean exists, boolean imported, String status) {
    }
}
