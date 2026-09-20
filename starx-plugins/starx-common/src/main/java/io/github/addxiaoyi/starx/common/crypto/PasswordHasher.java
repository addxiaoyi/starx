package io.github.addxiaoyi.starx.common.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PasswordHasher {
    private static final int COST_FACTOR = 12;
    private static final BCrypt.Version VERSION = BCrypt.Version.VERSION_2A;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        Objects.requireNonNull(password, "password");
        BCrypt.Hasher hasher = requiresLongPasswordStrategy(password)
            ? BCrypt.with(LongPasswordStrategies.hashSha512(VERSION))
            : BCrypt.withDefaults();
        return hasher.hashToString(COST_FACTOR, password.toCharArray());
    }

    public static boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            BCrypt.Verifyer verifyer = requiresLongPasswordStrategy(password)
                ? BCrypt.verifyer(VERSION, LongPasswordStrategies.hashSha512(VERSION))
                : BCrypt.verifyer();
            return verifyer.verify(password.toCharArray(), hash.toCharArray()).verified;
        } catch (RuntimeException invalidHashOrPassword) {
            return false;
        }
    }

    private static boolean requiresLongPasswordStrategy(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length >= VERSION.allowedMaxPwLength;
    }
}
