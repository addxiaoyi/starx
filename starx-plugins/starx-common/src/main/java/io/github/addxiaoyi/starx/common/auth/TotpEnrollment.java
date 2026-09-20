package io.github.addxiaoyi.starx.common.auth;

import java.time.Instant;

public record TotpEnrollment(String secret, String otpauthUri, Instant expiresAt) {
}
