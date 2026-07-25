/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.http;

public interface RouteRegistrar {
    public void get(String var1, RouteHandler var2);

    public void post(String var1, RouteHandler var2);

    public void get(String var1, RouteHandler ... var2);

    public void post(String var1, RouteHandler ... var2);

    public static interface JsonHttpExchange {
        public int status();

        public JsonHttpExchange status(int var1);

        public <T> T bodyAsClass(Class<T> var1) throws Exception;

        public String queryParam(String var1);

        public void json(Object var1) throws Exception;
    }

    @FunctionalInterface
    public static interface RouteHandler {
        public void handle(JsonHttpExchange var1) throws Exception;
    }
}
