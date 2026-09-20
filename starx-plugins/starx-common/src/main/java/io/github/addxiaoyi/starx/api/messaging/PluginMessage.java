/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.messaging;

import java.util.Map;
import java.util.Objects;

public final class PluginMessage {
    private final String command;
    private final Map<String, Object> payload;

    public PluginMessage(String command, Map<String, Object> payload) {
        this.command = Objects.requireNonNull(command, "command");
        this.payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public String command() {
        return this.command;
    }

    public Map<String, Object> payload() {
        return this.payload;
    }
}
