/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import java.nio.ByteOrder;

public interface BytesFactory {
    public Bytes wrap(byte[] var1, ByteOrder var2);
}
