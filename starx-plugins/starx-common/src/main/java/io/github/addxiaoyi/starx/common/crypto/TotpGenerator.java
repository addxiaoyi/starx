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
import javax.crypto.spec.SecretKeySpec;

public final class TotpGenerator {
    private static final TimeBasedOneTimePasswordGenerator GENERATOR = new TimeBasedOneTimePasswordGenerator();
    private static final String ALGORITHM = "HmacSHA1";
    private static final String ALIAS = "HmacSHA1";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

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

    public static boolean verify(String base32Secret, String code, Instant instant) {
        return TotpGenerator.generate(base32Secret, instant).equals(code);
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
