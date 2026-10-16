/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.common.security.HttpConstants;
import io.github.addxiaoyi.starx.website.ControlledHttpClientProvider;
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
    private final HttpClient httpClient;
    private final ControlledHttpClientProvider controlledHttp;

    public JavaHttpTransport() {
        this.httpClient = null;
        this.controlledHttp = ControlledHttpClientProvider.getInstance();
    }

    public JavaHttpTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.controlledHttp = null;
    }

    @Override
    public CompletableFuture<Void> post(String url, String body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(HttpConstants.DEFAULT_REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        CompletableFuture<HttpResponse<Void>> responseFuture = this.controlledHttp != null
                ? this.controlledHttp.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                : this.httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
        return responseFuture
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new WebhookDeliveryException(status);
                    }
                });
    }
}
