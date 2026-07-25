/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.model;

import java.util.UUID;

public final class StaffVote {
    private final String id;
    private final UUID targetUuid;
    private final String targetName;
    private final String reason;
    private final String voteType;
    private final String status;
    private final UUID initiatorUuid;
    private final String initiatorName;
    private final int yesVotes;
    private final int noVotes;
    private final int requiredYes;
    private final long expiresAt;
    private final long createdAt;
    private final Long resolvedAt;

    public StaffVote(String id, UUID targetUuid, String targetName, String reason, String voteType, String status, UUID initiatorUuid, String initiatorName, int yesVotes, int noVotes, int requiredYes, long expiresAt, long createdAt, Long resolvedAt) {
        this.id = id;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.reason = reason;
        this.voteType = voteType;
        this.status = status;
        this.initiatorUuid = initiatorUuid;
        this.initiatorName = initiatorName;
        this.yesVotes = yesVotes;
        this.noVotes = noVotes;
        this.requiredYes = requiredYes;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public String id() {
        return this.id;
    }

    public UUID targetUuid() {
        return this.targetUuid;
    }

    public String targetName() {
        return this.targetName;
    }

    public String reason() {
        return this.reason;
    }

    public String voteType() {
        return this.voteType;
    }

    public String status() {
        return this.status;
    }

    public UUID initiatorUuid() {
        return this.initiatorUuid;
    }

    public String initiatorName() {
        return this.initiatorName;
    }

    public int yesVotes() {
        return this.yesVotes;
    }

    public int noVotes() {
        return this.noVotes;
    }

    public int requiredYes() {
        return this.requiredYes;
    }

    public long expiresAt() {
        return this.expiresAt;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public Long resolvedAt() {
        return this.resolvedAt;
    }

    public boolean isActive() {
        return "ACTIVE".equals(this.status) && System.currentTimeMillis() < this.expiresAt;
    }
}
