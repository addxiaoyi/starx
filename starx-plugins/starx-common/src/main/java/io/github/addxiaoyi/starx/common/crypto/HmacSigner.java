/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSigner {
    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacSigner() {
    }

    public static String sign(String secret, String rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HmacSigner.bytesToHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    public static boolean verify(String secret, String rawBody, String signature) {
        if (signature == null || !signature.matches("[0-9a-fA-F]{64}")) return false;
        String expected = HmacSigner.sign(secret, rawBody);
        return constantTimeEquals(expected, signature.toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0xF];
        }
        return new String(chars);
    }
}
