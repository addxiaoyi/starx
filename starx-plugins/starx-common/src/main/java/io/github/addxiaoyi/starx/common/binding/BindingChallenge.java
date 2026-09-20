package io.github.addxiaoyi.starx.common.binding;

public record BindingChallenge(
    String id,
    String accountId,
    String kind,
    String payload,
    String tokenHash,
    BindingState state,
    long createdAt,
    long expiresAt
) {}
