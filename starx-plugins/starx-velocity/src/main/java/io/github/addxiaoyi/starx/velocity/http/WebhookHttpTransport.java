/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface WebhookHttpTransport {
    public CompletableFuture<Void> post(String var1, String var2, Map<String, String> var3);
}
