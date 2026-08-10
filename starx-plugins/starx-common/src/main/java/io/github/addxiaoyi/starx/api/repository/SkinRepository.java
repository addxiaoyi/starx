/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.repository;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import java.util.Optional;
import java.util.UUID;

public interface SkinRepository {
    public Optional<SkinDto> findByPlayer(UUID var1, String var2);

    public void setSkinId(UUID var1, String var2);

    public void setSkinData(UUID var1, String var2, String var3);

    public void clearSkin(UUID var1);

    /**
     * Reports whether this repository can participate in skin refreshes through its provider.
     * The default keeps older third-party implementations source and binary compatible.
     */
    default boolean isAvailable() {
        return true;
    }

    default boolean trySetSkinId(UUID uuid, String skinId) {
        this.setSkinId(uuid, skinId);
        return true;
    }

    default boolean trySetSkinData(UUID uuid, String value, String signature) {
        this.setSkinData(uuid, value, signature);
        return true;
    }

    default boolean tryClearSkin(UUID uuid) {
        this.clearSkin(uuid);
        return true;
    }
}
