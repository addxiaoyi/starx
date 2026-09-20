/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.api.dto.WebhookPayload;
import com.google.gson.Gson;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.http.JavaHttpTransport;
import io.github.addxiaoyi.starx.velocity.http.WebhookHttpTransport;
import io.github.addxiaoyi.starx.velocity.security.WebhookSigner;
import java.util.Map;
import java.util.Objects;
import java.net.URI;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class WebhookClient {
    public static final String SIGNATURE_HEADER = "X-StarX-Signature";
    public static final String TIMESTAMP_HEADER = "X-StarX-Timestamp";
    private final StarxConfig.WebhookConfig config;
    private final WebhookSigner signer;
    private final WebhookHttpTransport transport;
    private final Clock clock;
    private final int maxAttempts;
    private final long baseDelayMillis;
    private final FileWebhookOutbox outbox;
    private final Gson gson = new Gson();

    public WebhookClient(StarxConfig.WebhookConfig config, WebhookSigner signer) {
        this(config, signer, new JavaHttpTransport(), Clock.systemUTC(), 3, 250L, null);
    }

    public WebhookClient(StarxConfig.WebhookConfig config, WebhookSigner signer, WebhookHttpTransport transport) {
        this(config, signer, transport, Clock.systemUTC(), 3, 250L, null);
    }

    public WebhookClient(
            StarxConfig.WebhookConfig config,
            WebhookSigner signer,
            FileWebhookOutbox outbox) {
        this(config, signer, new JavaHttpTransport(), Clock.systemUTC(), 3, 250L, outbox);
    }

    WebhookClient(StarxConfig.WebhookConfig config, WebhookSigner signer, WebhookHttpTransport transport, Clock clock) {
        this(config, signer, transport, clock, 3, 250L, null);
    }

    WebhookClient(
            StarxConfig.WebhookConfig config,
            WebhookSigner signer,
            WebhookHttpTransport transport,
            Clock clock,
            int maxAttempts,
            long baseDelayMillis) {
        this(config, signer, transport, clock, maxAttempts, baseDelayMillis, null);
    }

    WebhookClient(
            StarxConfig.WebhookConfig config,
            WebhookSigner signer,
            WebhookHttpTransport transport,
            Clock clock,
            int maxAttempts,
            long baseDelayMillis,
            FileWebhookOutbox outbox) {
        this.config = Objects.requireNonNull(config, "config");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAttempts < 1 || baseDelayMillis < 0L) {
            throw new IllegalArgumentException("Invalid webhook retry policy");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.outbox = outbox;
    }

    public CompletableFuture<Void> send(WebhookPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!this.config.isConfigured()) {
            return CompletableFuture.completedFuture(null);
        }
        String body = this.serialize(payload);
        if (this.outbox == null) return this.deliver(this.config.url(), body, 1);
        PendingWebhook pending = this.outbox.enqueue(this.config.url(), body, this.clock.millis());
        return this.deliverPending(pending);
    }

    public CompletableFuture<Void> replayPending() {
        if (this.outbox == null) return CompletableFuture.completedFuture(null);
        java.util.concurrent.atomic.AtomicReference<Throwable> firstFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (PendingWebhook pending : this.outbox.pending()) {
            chain = chain.thenCompose(ignored -> this.deliverPending(pending)
                    .exceptionally(error -> {
                        firstFailure.compareAndSet(null, unwrap(error));
                        return null;
                    }));
        }
        return chain.thenCompose(ignored -> {
            Throwable error = firstFailure.get();
            return error == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(error);
        });
    }

    private CompletableFuture<Void> deliverPending(PendingWebhook pending) {
        return this.deliver(pending.url(), pending.body(), 1)
                .thenRun(() -> this.outbox.ack(pending.id()));
    }

    public CompletableFuture<Void> post(String url, Map<String, Object> body) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(body, "body");
        String json = this.gson.toJson(body);
        return this.deliver(url, json, 1);
    }

    private String serialize(WebhookPayload payload) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("eventId", payload.eventId().toString());
        map.put("eventType", payload.eventType());
        map.put("timestamp", payload.timestamp().toString());
        map.put("data", payload.data());
        return this.gson.toJson(map);
    }

    private Map<String, String> signedHeaders(String url, String body) {
        String timestamp = String.valueOf(this.clock.millis());
        String signature = this.signer.signRequest(
                "POST", requestTarget(url), timestamp, body);
        return signature.isBlank()
                ? Map.of("Content-Type", "application/json")
                : Map.of(
                        "Content-Type", "application/json",
                        SIGNATURE_HEADER, signature,
                        TIMESTAMP_HEADER, timestamp);
    }

    private CompletableFuture<Void> deliver(String url, String body, int attempt) {
        Map<String, String> headers = this.signedHeaders(url, body);
        CompletableFuture<Void> delivery;
        try {
            delivery = this.transport.post(url, body, headers);
        } catch (RuntimeException error) {
            delivery = CompletableFuture.failedFuture(error);
        }
        return delivery.handle((ignored, error) -> {
            if (error == null) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            Throwable cause = unwrap(error);
            if (attempt >= this.maxAttempts || !isRetryable(cause)) {
                return CompletableFuture.<Void>failedFuture(cause);
            }
            long delay = this.baseDelayMillis * (1L << Math.min(attempt - 1, 10));
            return CompletableFuture.runAsync(
                    () -> {}, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                    .thenCompose(next -> this.deliver(url, body, attempt + 1));
        }).thenCompose(next -> next);
    }

    private static boolean isRetryable(Throwable error) {
        if (!(error instanceof WebhookDeliveryException delivery)) {
            return true;
        }
        int status = delivery.statusCode();
        return status < 0 || status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String requestTarget(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        return query == null ? path : path + "?" + query;
    }
}
