package io.github.addxiaoyi.starx.velocity.security;

import io.github.addxiaoyi.starx.common.crypto.HmacSigner;
import io.github.addxiaoyi.starx.common.crypto.HmacRequestSigner;

import java.nio.charset.StandardCharsets;

public final class HmacWebhookSigner implements WebhookSigner {
    private final String secret;

    public HmacWebhookSigner(String secret) {
        this.secret = secret;
    }

    @Override
    public String sign(String payload) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        return HmacSigner.sign(secret, payload);
    }

    @Override
    public String signRequest(String method, String target, String timestamp, String payload) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        return HmacRequestSigner.sign(secret, method, target, timestamp, payload);
    }
}
