/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.BytesFactory;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

public final class ReadOnlyBytes
extends Bytes {
    ReadOnlyBytes(byte[] byteArray, ByteOrder byteOrder) {
        super(byteArray, byteOrder, new Factory());
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public byte[] array() {
        throw new ReadOnlyBufferException();
    }

    private static class Factory
    implements BytesFactory {
        private Factory() {
        }

        @Override
        public Bytes wrap(byte[] array, ByteOrder byteOrder) {
            return new ReadOnlyBytes(array, byteOrder);
        }
    }
}
