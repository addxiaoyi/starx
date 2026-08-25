/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.security;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HttpClient {
    private static final Logger logger = Logger.getLogger(HttpClient.class.getName());
    private static final Gson gson = new Gson();
    private static final java.net.http.HttpClient sharedClient = java.net.http.HttpClient.newBuilder().connectTimeout(HttpConstants.DEFAULT_CONNECT_TIMEOUT).build();
    private final java.net.http.HttpClient client;
    private final String url;
    private final String method;
    private String bearerToken;
    private HttpRequest.BodyPublisher bodyPublisher;

    private HttpClient(java.net.http.HttpClient client, String method, String url) {
        this.client = Objects.requireNonNull(client, "client");
        this.method = Objects.requireNonNull(method, "method");
        this.url = Objects.requireNonNull(url, "url");
    }

    public static HttpClient get(String url) {
        return new HttpClient(sharedClient, "GET", url);
    }

    public static HttpClient post(String url) {
        return new HttpClient(sharedClient, "POST", url);
    }

    public HttpClient bearer(String token) {
        this.bearerToken = token;
        return this;
    }

    public HttpClient bodyJson(Object body) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8);
        return this;
    }

    public <T> T sendJson(Class<T> responseType) {
        Response<T> response = this.send(responseType);
        return response.statusCode() >= 200 && response.statusCode() < 300 ? response.body() : null;
    }

    public <T> Response<T> send(Class<T> responseType) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(this.url)).timeout(HttpConstants.DEFAULT_REQUEST_TIMEOUT).header("Content-Type", "application/json").header("Accept", "application/json");
            if (this.bearerToken != null) {
                builder.header("Authorization", "Bearer " + this.bearerToken);
            }
            if (this.bodyPublisher != null) {
                builder.method(this.method, this.bodyPublisher);
            } else {
                builder.method(this.method, HttpRequest.BodyPublishers.noBody());
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            T body = null;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                body = gson.fromJson(response.body(), responseType);
            }
            return new Response<>(response.statusCode(), body, null);
        }
        catch (Exception e) {
            logger.log(Level.WARNING, "HTTP request failed: {0} {1}", new Object[]{this.method, this.url});
            return new Response<>(0, null, e);
        }
    }

    public record Response<T>(int statusCode, T body, Exception error) {}
}
