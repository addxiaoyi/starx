/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.model;

public final class Announcement {
    private final String id;
    private final String title;
    private final String content;
    private final String createdBy;
    private final long createdAt;
    private final Long expiresAt;

    public Announcement(String id, String title, String content, String createdBy, long createdAt, Long expiresAt) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String id() {
        return this.id;
    }

    public String title() {
        return this.title;
    }

    public String content() {
        return this.content;
    }

    public String createdBy() {
        return this.createdBy;
    }

    public long createdAt() {
        return this.createdAt;
    }

    public Long expiresAt() {
        return this.expiresAt;
    }
}
