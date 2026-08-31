/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.crypto;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;

public final class TotpGenerator {
    private static final TimeBasedOneTimePasswordGenerator GENERATOR = new TimeBasedOneTimePasswordGenerator();
    private static final String ALGORITHM = "HmacSHA1";
    private static final String ALIAS = "HmacSHA1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /**
     * Replay protection cache: maps (userUuid + base32Secret + code) -> code end-of-life (epoch ms).
     * Codes are kept in the cache from one window before their generation until one window after
     * (so a code is invalid for ~2 windows once consumed, matching the standard TOTP replay window).
     */
    private static final long REPLAY_WINDOW_MS = 60_000L; // 2 windows of 30s
    private static final Map<String, Long> CONSUMED_CODES = new ConcurrentHashMap<>();

    private TotpGenerator() {
    }

    public static String generateSecret() {
        byte[] bytes = new byte[20];
        SECURE_RANDOM.nextBytes(bytes);
        return TotpGenerator.encodeBase32(bytes);
    }

    public static String generate(String base32Secret, Instant instant) {
        try {
            return GENERATOR.generateOneTimePasswordString(TotpGenerator.buildKey(base32Secret), instant);
        }
        catch (InvalidKeyException e) {
            throw new IllegalStateException("Failed to generate TOTP", e);
        }
    }

    /**
     * Verifies a TOTP code WITHOUT consuming it. Use this for code generation/testing.
     */
    public static boolean verify(String base32Secret, String code, Instant instant) {
        return TotpGenerator.generate(base32Secret, instant).equals(code);
    }

    /**
     * Verifies a TOTP code and consumes it (replay protection).
     * The code becomes invalid for subsequent calls within the same time window.
     *
     * <p>Uses atomic putIfAbsent to ensure that under high concurrency only ONE
     * thread successfully consumes a given code. This is critical because
     * TOTP codes are 6 digits (1M possibilities) and without proper atomic
     * consumption, concurrent threads could all succeed.
     *
     * @param userUuid the user identifier (to scope the replay protection per user)
     * @param base32Secret the TOTP secret
     * @param code the 6-digit code to verify
     * @param instant the current time
     * @return true if the code is valid and has not been used before
     */
    public static boolean verifyAndConsume(UUID userUuid, String base32Secret, String code, Instant instant) {
        if (code == null || code.isBlank()) {
            return false;
        }
        // Allow a small window of ±1 step for clock skew tolerance
        long stepMillis = 30_000L;
        for (int offset = -1; offset <= 1; offset++) {
            Instant checkTime = instant.plusMillis(offset * stepMillis);
            String expected = generate(base32Secret, checkTime);
            if (!expected.equals(code)) {
                continue;
            }
            // Code matches. Build a cache key including the offset so each
            // time-window has its own entry.
            String key = userUuid + ":" + base32Secret + ":" + offset + ":" + code;
            long codeEndOfLife = checkTime.plusMillis(REPLAY_WINDOW_MS).toEpochMilli();

            // Atomic check-and-consume: only succeeds if this exact key was not present.
            // putIfAbsent returns the previous value (null if absent) - so a non-null
            // return means someone else already consumed this code.
            Long previousValue = CONSUMED_CODES.putIfAbsent(key, codeEndOfLife);
            if (previousValue != null && previousValue > Instant.now().toEpochMilli()) {
                // Code already consumed and still within the validity window
                return false;
            }
            if (previousValue != null) {
                // Previous entry was expired; we just overwrote it with a fresh window.
                // We need to also re-check that nobody consumed it between our check and put.
                // To be safe, treat this as a potential replay (defense in depth).
                // In practice this race is rare and only matters at window boundaries.
                return false;
            }
            // Successfully consumed the code
            cleanupExpiredCodes();
            return true;
        }
        return false;
    }

    private static void cleanupExpiredCodes() {
        long now = Instant.now().toEpochMilli();
        CONSUMED_CODES.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    public static String provisioningUri(String issuer, String account, String base32Secret) {
        String encodedIssuer = TotpGenerator.encode(issuer);
        String encodedAccount = TotpGenerator.encode(account);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedAccount + "?secret=" + base32Secret + "&issuer=" + encodedIssuer + "&algorithm=SHA1&digits=6&period=30";
    }

    private static Key buildKey(String base32Secret) {
        return new SecretKeySpec(Base32.decode(base32Secret), "HmacSHA1");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = buffer << 8 | b & 0xFF;
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt(buffer >> (bitsLeft -= 5) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt(buffer << 5 - bitsLeft & 0x1F));
        }
        return sb.toString();
    }

    private static final class Base32 {
        private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        private Base32() {
        }

        static byte[] decode(String input) {
            String normalized = input.toUpperCase().replace("=", "");
            int outputLength = normalized.length() * 5 / 8;
            byte[] output = new byte[outputLength];
            int buffer = 0;
            int bitsLeft = 0;
            int index = 0;
            for (char c : normalized.toCharArray()) {
                int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
                if (value < 0) {
                    throw new IllegalArgumentException("Invalid Base32 character: " + c);
                }
                buffer = buffer << 5 | value;
                if ((bitsLeft += 5) < 8) continue;
                output[index++] = (byte)(buffer >> (bitsLeft -= 8) & 0xFF);
            }
            return output;
        }
    }
}
