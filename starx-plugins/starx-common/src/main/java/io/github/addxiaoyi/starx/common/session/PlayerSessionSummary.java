package io.github.addxiaoyi.starx.common.session;

public record PlayerSessionSummary(long totalPlaytime, int loginCount, String lastServer,
                                   DisconnectReason disconnectReason) {}
