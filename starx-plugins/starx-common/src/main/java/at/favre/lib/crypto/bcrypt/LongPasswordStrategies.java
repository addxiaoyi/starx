/*
 * Decompiled with CFR 0.152.
 */
package at.favre.lib.crypto.bcrypt;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategy;
import java.util.Objects;

public final class LongPasswordStrategies {
    private LongPasswordStrategies() {
    }

    public static LongPasswordStrategy truncate(BCrypt.Version version) {
        return new LongPasswordStrategy.TruncateStrategy(Objects.requireNonNull(version).allowedMaxPwLength);
    }

    public static LongPasswordStrategy hashSha512(BCrypt.Version version) {
        return new LongPasswordStrategy.Sha512DerivationStrategy(Objects.requireNonNull(version).allowedMaxPwLength);
    }

    public static LongPasswordStrategy strict(BCrypt.Version version) {
        return new LongPasswordStrategy.StrictMaxPasswordLengthStrategy(Objects.requireNonNull(version).allowedMaxPwLength);
    }

    public static LongPasswordStrategy none() {
        return new LongPasswordStrategy.PassThroughStrategy();
    }
}
