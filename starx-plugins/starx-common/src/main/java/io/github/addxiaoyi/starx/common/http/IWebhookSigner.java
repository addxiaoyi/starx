package io.github.addxiaoyi.starx.common.http;

/**
 * Interface for signing webhook requests.
 */
public interface IWebhookSigner {
    /**
     * Sign a webhook payload.
     *
     * @param payload The raw payload body
     * @param timestamp Current timestamp in milliseconds
     * @return The signature to include in the request header
     */
    String sign(byte[] payload, long timestamp);
}
