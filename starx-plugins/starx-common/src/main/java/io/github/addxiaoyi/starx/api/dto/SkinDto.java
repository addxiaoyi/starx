/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.api.dto;

import java.util.Objects;
import java.util.UUID;

public final class SkinDto {
    private final UUID ownerUuid;
    private final String ownerName;
    private final String skinId;
    private final String value;
    private final String signature;
    private final String textureUrl;

    public SkinDto(UUID ownerUuid, String ownerName, String skinId, String value, String signature, String textureUrl) {
        this.ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.skinId = skinId;
        this.value = value;
        this.signature = signature;
        this.textureUrl = textureUrl;
    }

    public UUID ownerUuid() {
        return this.ownerUuid;
    }

    public String ownerName() {
        return this.ownerName;
    }

    public String skinId() {
        return this.skinId;
    }

    public String value() {
        return this.value;
    }

    public String signature() {
        return this.signature;
    }

    public String textureUrl() {
        return this.textureUrl;
    }
}
