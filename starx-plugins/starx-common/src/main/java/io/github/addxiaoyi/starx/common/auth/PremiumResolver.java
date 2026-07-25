/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import java.util.UUID;

public final class PremiumResolver {
    public PremiumResolver() {
    }

    public boolean isPremium(UUID uuid, boolean onlineMode) {
        return onlineMode && uuid.version() == 4;
    }
}
