/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.http;

import io.github.addxiaoyi.starx.velocity.http.JsonHttpExchange;

public interface RouteRegistrar {
    public void get(String var1, RouteHandler var2);

    public void post(String var1, RouteHandler var2);

    public void get(String var1, RouteHandler ... var2);

    public void post(String var1, RouteHandler ... var2);

    @FunctionalInterface
    public static interface RouteHandler {
        public void handle(JsonHttpExchange var1) throws Exception;
    }
}
