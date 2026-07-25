/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.Util;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public interface BytesTransformer {
    public byte[] transform(byte[] var1, boolean var2);

    public boolean supportInPlaceTransformation();

    public static class MessageDigestTransformer
    implements BytesTransformer {
        static final String ALGORITHM_MD5 = "MD5";
        static final String ALGORITHM_SHA_1 = "SHA-1";
        static final String ALGORITHM_SHA_256 = "SHA-256";
        private final MessageDigest messageDigest;

        MessageDigestTransformer(String digestName) {
            try {
                this.messageDigest = MessageDigest.getInstance(digestName);
            }
            catch (NoSuchAlgorithmException e) {
                throw new IllegalArgumentException("could not get message digest algorithm " + digestName, e);
            }
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            this.messageDigest.update(currentArray);
            return this.messageDigest.digest();
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }
    }

    public static class BitSwitchTransformer
    implements BytesTransformer {
        private final int position;
        private final Boolean newBitValue;

        BitSwitchTransformer(int position, Boolean newBitValue) {
            this.position = position;
            this.newBitValue = newBitValue;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] out;
            byte[] byArray = out = inPlace ? currentArray : Bytes.from(currentArray).array();
            if (this.position < 0 || this.position >= 8 * currentArray.length) {
                throw new IllegalArgumentException("bit index " + this.position * 8 + " out of bounds");
            }
            int bytePosition = currentArray.length - 1 - this.position / 8;
            if (this.newBitValue == null) {
                int n = bytePosition;
                out[n] = (byte)(out[n] ^ 1 << this.position % 8);
            } else if (this.newBitValue.booleanValue()) {
                int n = bytePosition;
                out[n] = (byte)(out[n] | 1 << this.position % 8);
            } else {
                int n = bytePosition;
                out[n] = (byte)(out[n] & ~(1 << this.position % 8));
            }
            return out;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }
    }

    public static final class ResizeTransformer
    implements BytesTransformer {
        private final int newSize;
        private final Mode mode;

        ResizeTransformer(int newSize, Mode mode) {
            this.newSize = newSize;
            this.mode = mode;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            if (currentArray.length == this.newSize) {
                return currentArray;
            }
            if (this.newSize < 0) {
                throw new IllegalArgumentException("cannot resize to smaller than 0");
            }
            if (this.newSize == 0) {
                return new byte[0];
            }
            byte[] resizedArray = new byte[this.newSize];
            if (this.mode == Mode.RESIZE_KEEP_FROM_MAX_LENGTH) {
                if (this.newSize > currentArray.length) {
                    System.arraycopy(currentArray, 0, resizedArray, Math.max(0, Math.abs(this.newSize - currentArray.length)), Math.min(this.newSize, currentArray.length));
                } else {
                    System.arraycopy(currentArray, Math.max(0, Math.abs(this.newSize - currentArray.length)), resizedArray, Math.min(0, Math.abs(this.newSize - currentArray.length)), Math.min(this.newSize, currentArray.length));
                }
            } else {
                System.arraycopy(currentArray, 0, resizedArray, 0, Math.min(currentArray.length, resizedArray.length));
            }
            return resizedArray;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }

        public static enum Mode {
            RESIZE_KEEP_FROM_ZERO_INDEX,
            RESIZE_KEEP_FROM_MAX_LENGTH;

        }
    }

    public static final class CopyTransformer
    implements BytesTransformer {
        final int offset;
        final int length;

        CopyTransformer(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] copy = new byte[this.length];
            System.arraycopy(currentArray, this.offset, copy, 0, copy.length);
            return copy;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }
    }

    public static final class ReverseTransformer
    implements BytesTransformer {
        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] out = inPlace ? currentArray : Bytes.from(currentArray).array();
            Util.Byte.reverse(out, 0, out.length);
            return out;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }
    }

    public static final class ConcatTransformer
    implements BytesTransformer {
        private final byte[] secondArray;

        ConcatTransformer(byte[] secondArrays) {
            this.secondArray = Objects.requireNonNull(secondArrays, "the second byte array must not be null");
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            return Util.Byte.concat(currentArray, this.secondArray);
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }
    }

    public static final class ShiftTransformer
    implements BytesTransformer {
        private final int shiftCount;
        private final Type type;
        private final ByteOrder byteOrder;

        ShiftTransformer(int shiftCount, Type type, ByteOrder byteOrder) {
            this.shiftCount = shiftCount;
            this.type = Objects.requireNonNull(type, "passed shift type must not be null");
            this.byteOrder = Objects.requireNonNull(byteOrder, "passed byteOrder type must not be null");
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] out = inPlace ? currentArray : Bytes.from(currentArray).array();
            switch (this.type) {
                case RIGHT_SHIFT: {
                    return Util.Byte.shiftRight(out, this.shiftCount, this.byteOrder);
                }
            }
            return Util.Byte.shiftLeft(out, this.shiftCount, this.byteOrder);
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }

        public static enum Type {
            LEFT_SHIFT,
            RIGHT_SHIFT;

        }
    }

    public static final class NegateTransformer
    implements BytesTransformer {
        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] out = inPlace ? currentArray : Bytes.from(currentArray).array();
            for (int i = 0; i < out.length; ++i) {
                out[i] = (byte) ~out[i];
            }
            return out;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }
    }

    public static final class BitWiseOperatorTransformer
    implements BytesTransformer {
        private final byte[] secondArray;
        private final Mode mode;

        BitWiseOperatorTransformer(byte[] secondArray, Mode mode) {
            this.secondArray = Objects.requireNonNull(secondArray, "the second byte array must not be null");
            this.mode = Objects.requireNonNull(mode, "passed bitwise mode must not be null");
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            if (currentArray.length != this.secondArray.length) {
                throw new IllegalArgumentException("all byte array must be of same length doing bit wise operation");
            }
            byte[] out = inPlace ? currentArray : new byte[currentArray.length];
            block4: for (int i = 0; i < currentArray.length; ++i) {
                switch (this.mode) {
                    case AND: {
                        out[i] = (byte)(currentArray[i] & this.secondArray[i]);
                        continue block4;
                    }
                    case XOR: {
                        out[i] = (byte)(currentArray[i] ^ this.secondArray[i]);
                        continue block4;
                    }
                    default: {
                        out[i] = (byte)(currentArray[i] | this.secondArray[i]);
                    }
                }
            }
            return out;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }

        public static enum Mode {
            AND,
            OR,
            XOR;

        }
    }
}
