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
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SkinService {
    private static final Logger LOGGER = Logger.getLogger(SkinService.class.getName());
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
            boolean stored;
            try {
                if (data.skinId() != null) {
                    stored = this.skinRepository.trySetSkinId(uuid, data.skinId());
                } else if (data.value() != null && data.signature() != null) {
                    stored = this.skinRepository.trySetSkinData(uuid, data.value(), data.signature());
                } else {
                    stored = false;
                }
            } catch (RuntimeException | LinkageError error) {
                LOGGER.log(Level.WARNING, "Failed to persist skin for " + uuid, error);
                return;
            }
            if (!stored) {
                LOGGER.warning("Skin repository did not persist skin for " + uuid);
                return;
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
