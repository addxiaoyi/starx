/*
 * Decompiled with CFR 0.152.
 */
package at.favre.lib.crypto.bcrypt;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.BytesTransformer;

public interface LongPasswordStrategy {
    public byte[] derive(byte[] var1);

    public static final class PassThroughStrategy
    implements LongPasswordStrategy {
        @Override
        public byte[] derive(byte[] rawPassword) {
            return rawPassword;
        }
    }

    public static final class TruncateStrategy
    extends BaseLongPasswordStrategy {
        TruncateStrategy(int maxLength) {
            super(maxLength);
        }

        @Override
        public byte[] innerDerive(byte[] rawPassword) {
            return Bytes.wrap(rawPassword).resize(this.maxLength, BytesTransformer.ResizeTransformer.Mode.RESIZE_KEEP_FROM_ZERO_INDEX).array();
        }
    }

    public static final class Sha512DerivationStrategy
    extends BaseLongPasswordStrategy {
        Sha512DerivationStrategy(int maxLength) {
            super(maxLength);
        }

        @Override
        public byte[] innerDerive(byte[] rawPassword) {
            return Bytes.wrap(rawPassword).hash("SHA-512").array();
        }
    }

    public static final class StrictMaxPasswordLengthStrategy
    extends BaseLongPasswordStrategy {
        StrictMaxPasswordLengthStrategy(int maxLength) {
            super(maxLength);
        }

        @Override
        public byte[] innerDerive(byte[] rawPassword) {
            throw new IllegalArgumentException("password must not be longer than " + this.maxLength + " bytes plus null terminator encoded in utf-8, was " + rawPassword.length);
        }
    }

    public static abstract class BaseLongPasswordStrategy
    implements LongPasswordStrategy {
        final int maxLength;

        private BaseLongPasswordStrategy(int maxLength) {
            this.maxLength = maxLength;
        }

        abstract byte[] innerDerive(byte[] var1);

        @Override
        public byte[] derive(byte[] rawPassword) {
            if (rawPassword.length >= this.maxLength) {
                return this.innerDerive(rawPassword);
            }
            return rawPassword;
        }
    }
}
