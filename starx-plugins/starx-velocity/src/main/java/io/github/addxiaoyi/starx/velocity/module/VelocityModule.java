/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module;

public interface VelocityModule {
    public String name();

    default public void onEnable() {
    }

    default public void onShutdownStart() {
    }

    default public void onDisable() {
    }
}
