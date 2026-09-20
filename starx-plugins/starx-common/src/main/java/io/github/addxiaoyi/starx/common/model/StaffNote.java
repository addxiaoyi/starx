/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.model;

import java.util.UUID;

public final class StaffNote {
    private final String id;
    private final UUID targetUuid;
    private final String note;
    private final String severity;
    private final UUID staffUuid;
    private final long createdAt;

    public StaffNote(String id, UUID targetUuid, String note, String severity, UUID staffUuid, long createdAt) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.note = note;
        this.severity = severity;
        this.staffUuid = staffUuid;
        this.createdAt = createdAt;
    }

    public String id() {
        return this.id;
    }

    public UUID targetUuid() {
        return this.targetUuid;
    }

    public String note() {
        return this.note;
    }

    public String severity() {
        return this.severity;
    }

    public UUID staffUuid() {
        return this.staffUuid;
    }

    public long createdAt() {
        return this.createdAt;
    }
}
