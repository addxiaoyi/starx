/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.api.dto.WebhookPayload;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.velocity.http.WebhookClient;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookEventPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);
    private static final String[] SUBSCRIBED_EVENTS = new String[]{"player:login:success", "player:login:failed", "player:register", "player:brute-force", "skin:updated", "skin:applied", "admin:ban:player", "admin:kick:player"};
    private final EventBus eventBus;
    private final WebhookClient webhookClient;
    private final Consumer<StarxEvent> subscriber = this::onEvent;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    public WebhookEventPublisher(EventBus eventBus, WebhookClient webhookClient) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.webhookClient = Objects.requireNonNull(webhookClient, "webhookClient");
    }

    public void register() {
        if (!this.state.compareAndSet(State.NEW, State.REGISTERED)) {
            return;
        }
        for (String type : SUBSCRIBED_EVENTS) {
            this.eventBus.subscribe(type, this.subscriber);
        }
    }

    @Override
    public void close() {
        State previous = this.state.getAndSet(State.CLOSED);
        if (previous == State.REGISTERED) {
            for (String type : SUBSCRIBED_EVENTS) {
                this.eventBus.unsubscribe(type, this.subscriber);
            }
        }
    }

    private void onEvent(StarxEvent event) {
        this.webhookClient.send(new WebhookPayload(event.type(), event.payload()))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.warn("Webhook delivery failed for event {}", event.type(), error);
                    }
                });
    }

    private enum State {
        NEW,
        REGISTERED,
        CLOSED
    }
}
