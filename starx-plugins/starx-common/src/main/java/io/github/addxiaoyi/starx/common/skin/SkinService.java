/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.skin;

import io.github.addxiaoyi.starx.api.dto.SkinDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.repository.SkinRepository;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SkinService {
    private static final Logger LOGGER = Logger.getLogger(SkinService.class.getName());
    private final SkinRepository skinRepository;
    private final EventBus eventBus;
    private final Function<UUID, Set<UUID>> knownMinecraftUuidsResolver;

    public SkinService(SkinRepository skinRepository, EventBus eventBus) {
        this(skinRepository, eventBus, uuid -> Set.of(uuid));
    }

    public SkinService(
        SkinRepository skinRepository,
        EventBus eventBus,
        Function<UUID, Set<UUID>> knownMinecraftUuidsResolver) {
        this.skinRepository = Objects.requireNonNull(skinRepository, "skinRepository");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.knownMinecraftUuidsResolver = Objects.requireNonNull(
            knownMinecraftUuidsResolver, "knownMinecraftUuidsResolver");
    }

    public void refreshSkin(UUID uuid, String name) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Optional<SkinDto> skin = Optional.empty();
        for (UUID knownUuid : knownUuids(uuid)) {
            skin = this.skinRepository.findByPlayer(knownUuid, name);
            if (skin.isPresent()) break;
        }
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

    private Set<UUID> knownUuids(UUID requested) {
        LinkedHashSet<UUID> known = new LinkedHashSet<>();
        known.add(requested);
        Set<UUID> aliases = Objects.requireNonNull(
            this.knownMinecraftUuidsResolver.apply(requested),
            "knownMinecraftUuidsResolver returned null");
        aliases.stream().filter(Objects::nonNull).forEach(known::add);
        return known;
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
