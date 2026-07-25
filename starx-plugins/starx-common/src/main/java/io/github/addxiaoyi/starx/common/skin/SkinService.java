/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SkinService {
    private final SkinRepository skinRepository;
    private final EventBus eventBus;

    public SkinService(SkinRepository skinRepository, EventBus eventBus) {
        this.skinRepository = Objects.requireNonNull(skinRepository, "skinRepository");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    public void refreshSkin(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Optional<SkinDto> skin = this.skinRepository.findByPlayer(uuid, name);
        if (skin.isPresent()) {
            SkinDto data = skin.get();
            if (data.skinId() != null) {
                this.skinRepository.setSkinId(uuid, data.skinId());
            } else if (data.value() != null && data.signature() != null) {
                this.skinRepository.setSkinData(uuid, data.value(), data.signature());
            }
            this.applySkin(uuid);
            this.notifySkinUpdated(uuid);
            return;
        }
        this.eventBus.publish("skin:refresh:request", Map.of("uuid", uuid.toString(), "name", name));
    }

    public void applySkin(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        this.eventBus.publish("skin:applied", Map.of("uuid", uuid.toString()));
    }

    public void notifySkinUpdated(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        this.eventBus.publish("skin:updated", Map.of("uuid", uuid.toString()));
    }
}
