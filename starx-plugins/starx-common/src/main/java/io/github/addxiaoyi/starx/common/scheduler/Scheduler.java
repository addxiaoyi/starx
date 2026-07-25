/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.scheduler;

import java.util.concurrent.TimeUnit;

public interface Scheduler {
    public void runAsync(Runnable var1);

    public void runLater(Runnable var1, long var2, TimeUnit var4);

    public void runRepeating(Runnable var1, long var2, long var4, TimeUnit var6);
}
