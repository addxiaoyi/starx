/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.common.security.HttpConstants;
import io.github.addxiaoyi.starx.velocity.http.WebhookHttpTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class JavaHttpTransport
implements WebhookHttpTransport {
    private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder().connectTimeout(HttpConstants.DEFAULT_CONNECT_TIMEOUT).build();
    private final HttpClient httpClient;

    public JavaHttpTransport() {
        this(SHARED_CLIENT);
    }

    public JavaHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public CompletableFuture<Void> post(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(HttpConstants.DEFAULT_REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return this.httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new WebhookDeliveryException(status);
                    }
                });
    }
}
