/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.smart;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AdaptiveRateLimiter {
    private final int defaultMaxConnectionsPerSecond;
    private final int defaultMaxPingsPerSecond;
    private final AtomicInteger currentTps = new AtomicInteger(20);
    private final AtomicInteger currentMemoryPercent = new AtomicInteger(50);
    private final AtomicLong lastUpdateMs = new AtomicLong(System.currentTimeMillis());

    public AdaptiveRateLimiter(int defaultMaxConnectionsPerSecond, int defaultMaxPingsPerSecond) {
        this.defaultMaxConnectionsPerSecond = defaultMaxConnectionsPerSecond;
        this.defaultMaxPingsPerSecond = defaultMaxPingsPerSecond;
    }

    public void updateTps(int tps) {
        this.currentTps.set(Math.max(0, tps));
        this.lastUpdateMs.set(System.currentTimeMillis());
    }

    public void updateMemoryPercent(int percent) {
        this.currentMemoryPercent.set(Math.max(0, Math.min(100, percent)));
        this.lastUpdateMs.set(System.currentTimeMillis());
    }

    public LoadLevel evaluateLoad() {
        int tps = this.currentTps.get();
        int mem = this.currentMemoryPercent.get();
        if (tps < 10 || mem > 90) {
            return LoadLevel.CRITICAL;
        }
        if (tps < 15 || mem > 75) {
            return LoadLevel.HIGH;
        }
        if (tps < 18 || mem > 60) {
            return LoadLevel.MODERATE;
        }
        if (tps >= 20 && mem < 50) {
            return LoadLevel.LOW;
        }
        return LoadLevel.NORMAL;
    }

    public int maxConnectionsPerSecond() {
        return (int)((double)this.defaultMaxConnectionsPerSecond * this.multiplier());
    }

    public int maxPingsPerSecond() {
        return (int)((double)this.defaultMaxPingsPerSecond * this.multiplier());
    }

    private double multiplier() {
        switch (this.evaluateLoad().ordinal()) {
            case 0: {
                return 2.0;
            }
            case 1: {
                return 1.0;
            }
            case 2: {
                return 0.5;
            }
            case 3: {
                return 0.25;
            }
            case 4: {
                return 0.1;
            }
        }
        return 1.0;
    }

    public boolean isStale() {
        return System.currentTimeMillis() - this.lastUpdateMs.get() > 30000L;
    }

    int getCurrentTps() {
        return this.currentTps.get();
    }

    int getCurrentMemoryPercent() {
        return this.currentMemoryPercent.get();
    }

    public static enum LoadLevel {
        LOW,
        NORMAL,
        MODERATE,
        HIGH,
        CRITICAL;

    }
}
