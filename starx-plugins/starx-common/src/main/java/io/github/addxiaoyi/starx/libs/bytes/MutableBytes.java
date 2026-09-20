/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.BytesFactory;
import io.github.addxiaoyi.starx.libs.bytes.Util;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class MutableBytes
extends Bytes
implements AutoCloseable {
    MutableBytes(byte[] byteArray, ByteOrder byteOrder) {
        super(byteArray, byteOrder, new Factory());
    }

    public static MutableBytes allocate(int length) {
        return MutableBytes.allocate(length, (byte)0);
    }

    public static MutableBytes allocate(int length, byte defaultValue) {
        return Bytes.allocate(length, defaultValue).mutable();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    public MutableBytes overwrite(byte[] newArray) {
        return this.overwrite(newArray, 0);
    }

    public MutableBytes overwrite(Bytes newBytes) {
        return this.overwrite(newBytes, 0);
    }

    public MutableBytes overwrite(byte[] newArray, int offsetInternalArray) {
        Objects.requireNonNull(newArray, "must provide non-null array as source");
        System.arraycopy(newArray, 0, this.internalArray(), offsetInternalArray, newArray.length);
        return this;
    }

    public MutableBytes overwrite(Bytes newBytes, int offsetInternalArray) {
        return this.overwrite(Objects.requireNonNull(newBytes, "must provide non-null array as source").array(), offsetInternalArray);
    }

    public MutableBytes setByteAt(int index, byte newByte) {
        this.internalArray()[index] = newByte;
        return this;
    }

    public MutableBytes wipe() {
        return this.fill((byte)0);
    }

    public MutableBytes fill(byte fillByte) {
        Arrays.fill(this.internalArray(), fillByte);
        return this;
    }

    public MutableBytes secureWipe() {
        return this.secureWipe(new SecureRandom());
    }

    public MutableBytes secureWipe(SecureRandom random) {
        Objects.requireNonNull(random, "random param must not be null");
        if (this.length() > 0) {
            random.nextBytes(this.internalArray());
        }
        return this;
    }

    public Bytes immutable() {
        return Bytes.wrap(this.internalArray(), this.byteOrder());
    }

    @Override
    public int hashCode() {
        return Util.Obj.hashCode(this.internalArray(), this.byteOrder());
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public void close() {
        this.secureWipe();
    }

    private static class Factory
    implements BytesFactory {
        private Factory() {
        }

        @Override
        public Bytes wrap(byte[] array, ByteOrder byteOrder) {
            return new MutableBytes(array, byteOrder);
        }
    }
}
