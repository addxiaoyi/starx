/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class JsonHttpExchange {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final Gson GSON = new Gson();
    private final HttpExchange exchange;
    private byte[] rawBody;
    private int responseStatus = 200;
    private boolean authenticated;

    public JsonHttpExchange(HttpExchange exchange) {
        this.exchange = exchange;
    }

    public int status() {
        return this.responseStatus;
    }

    boolean authenticated() {
        return this.authenticated;
    }

    void markAuthenticated() {
        this.authenticated = true;
    }

    public JsonHttpExchange status(int code) {
        this.responseStatus = code;
        return this;
    }

    public void json(Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        this.exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        this.exchange.sendResponseHeaders(this.responseStatus, bytes.length);
        this.exchange.getResponseBody().write(bytes);
        this.exchange.getResponseBody().close();
    }

    public <T> T bodyAsClass(Class<T> clazz) throws Exception {
        return GSON.fromJson(this.bodyString(), clazz);
    }

    public String bodyString() {
        if (this.rawBody == null) {
            String contentLength = this.exchange.getRequestHeaders().getFirst("Content-Length");
            int declared;
            try {
                declared = contentLength == null ? -1 : Integer.parseInt(contentLength);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid Content-Length", error);
            }
            if (declared < -1) throw new IllegalArgumentException("Invalid Content-Length");
            if (declared > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Payload too large");
            }
            try (InputStream is = this.exchange.getRequestBody();){
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(0, declared));
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = is.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BODY_BYTES) throw new IllegalArgumentException("Payload too large");
                    out.write(buffer, 0, read);
                }
                this.rawBody = out.toByteArray();
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new String(this.rawBody, StandardCharsets.UTF_8);
    }

    public String queryParam(String name) {
        String query = this.exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length != 2 || !parts[0].equals(name)) continue;
            return decodeQueryValue(parts[1]);
        }
        return null;
    }

    static String decodeQueryValue(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid query encoding", error);
        }
    }

    public String header(String name) {
        return this.exchange.getRequestHeaders().getFirst(name);
    }

    public String requestMethod() {
        return this.exchange.getRequestMethod();
    }

    public String requestTarget() {
        return requestTarget(this.exchange.getRequestURI());
    }

    static String requestTarget(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }

    public InetSocketAddress getRemoteAddress() {
        return this.exchange.getRemoteAddress();
    }

    public void result(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        this.exchange.sendResponseHeaders(this.responseStatus, bytes.length);
        this.exchange.getResponseBody().write(bytes);
        this.exchange.getResponseBody().close();
    }

    public void sendError(int code, String message) throws IOException {
        this.status(code);
        this.json(Map.of("error", message));
    }
}
