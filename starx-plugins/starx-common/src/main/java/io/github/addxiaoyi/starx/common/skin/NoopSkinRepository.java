/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.util.Optional;
import java.util.UUID;

public final class NoopSkinRepository
implements SkinRepository {
    @Override
    public Optional<SkinDto> findByPlayer(UUID uuid, String name) {
        return Optional.empty();
    }

    @Override
    public void setSkinId(UUID uuid, String skinId) {
    }

    @Override
    public void setSkinData(UUID uuid, String value, String signature) {
    }

    @Override
    public void clearSkin(UUID uuid) {
    }
}
