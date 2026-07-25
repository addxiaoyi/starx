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
}
