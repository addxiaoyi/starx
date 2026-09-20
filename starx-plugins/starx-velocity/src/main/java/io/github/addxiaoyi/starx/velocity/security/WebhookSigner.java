package io.github.addxiaoyi.starx.velocity.security;

/** Webhook 签名接口 */
public interface WebhookSigner {
    String sign(String payload);

    default String signRequest(String method, String target, String timestamp, String payload) {
        return sign(payload);
    }
}
