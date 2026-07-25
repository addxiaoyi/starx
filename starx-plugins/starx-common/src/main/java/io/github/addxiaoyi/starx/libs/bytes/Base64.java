/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import java.util.Arrays;

final class Base64 {
    private static final byte[] MAP = new byte[]{65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 43, 47};
    private static final byte[] URL_MAP = new byte[]{65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 45, 95};

    private Base64() {
    }

    static byte[] decode(CharSequence in) {
        char c;
        int limit;
        for (limit = in.length(); limit > 0 && ((c = in.charAt(limit - 1)) == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t'); --limit) {
        }
        byte[] out = new byte[(int)((long)limit * 6L / 8L)];
        int outCount = 0;
        int inCount = 0;
        int word = 0;
        for (int pos = 0; pos < limit; ++pos) {
            int bits;
            char c2 = in.charAt(pos);
            if (c2 >= 'A' && c2 <= 'Z') {
                bits = c2 - 65;
            } else if (c2 >= 'a' && c2 <= 'z') {
                bits = c2 - 71;
            } else if (c2 >= '0' && c2 <= '9') {
                bits = c2 + 4;
            } else if (c2 == '+' || c2 == '-') {
                bits = 62;
            } else if (c2 == '/' || c2 == '_') {
                bits = 63;
            } else {
                if (c2 == '\n' || c2 == '\r' || c2 == ' ' || c2 == '\t') continue;
                throw new IllegalArgumentException("invalid character to decode: " + c2);
            }
            word = word << 6 | (byte)bits;
            if (++inCount % 4 != 0) continue;
            out[outCount++] = (byte)(word >> 16);
            out[outCount++] = (byte)(word >> 8);
            out[outCount++] = (byte)word;
        }
        int lastWordChars = inCount % 4;
        if (lastWordChars == 1) {
            return null;
        }
        if (lastWordChars == 2) {
            out[outCount++] = (byte)((word <<= 12) >> 16);
        } else if (lastWordChars == 3) {
            out[outCount++] = (byte)((word <<= 6) >> 16);
            out[outCount++] = (byte)(word >> 8);
        }
        if (outCount == out.length) {
            return out;
        }
        return Arrays.copyOfRange(out, 0, outCount);
    }

    static byte[] encode(byte[] in) {
        return Base64.encode(in, false, true);
    }

    static byte[] encode(byte[] in, boolean urlSafe, boolean usePadding) {
        return Base64.encode(in, urlSafe ? URL_MAP : MAP, usePadding);
    }

    private static byte[] encode(byte[] in, byte[] map, boolean usePadding) {
        int length = Base64.outLength(in.length, usePadding);
        byte[] out = new byte[length];
        int index = 0;
        int end = in.length - in.length % 3;
        for (int i = 0; i < end; i += 3) {
            out[index++] = map[(in[i] & 0xFF) >> 2];
            out[index++] = map[(in[i] & 3) << 4 | (in[i + 1] & 0xFF) >> 4];
            out[index++] = map[(in[i + 1] & 0xF) << 2 | (in[i + 2] & 0xFF) >> 6];
            out[index++] = map[in[i + 2] & 0x3F];
        }
        switch (in.length % 3) {
            case 1: {
                out[index++] = map[(in[end] & 0xFF) >> 2];
                out[index++] = map[(in[end] & 3) << 4];
                if (!usePadding) break;
                out[index++] = 61;
                out[index] = 61;
                break;
            }
            case 2: {
                out[index++] = map[(in[end] & 0xFF) >> 2];
                out[index++] = map[(in[end] & 3) << 4 | (in[end + 1] & 0xFF) >> 4];
                out[index++] = map[(in[end + 1] & 0xF) << 2];
                if (!usePadding) break;
                out[index] = 61;
            }
        }
        return out;
    }

    private static int outLength(int srclen, boolean doPadding) {
        int len;
        if (doPadding) {
            len = 4 * ((srclen + 2) / 3);
        } else {
            int n = srclen % 3;
            len = 4 * (srclen / 3) + (n == 0 ? 0 : n + 1);
        }
        return len;
    }
}
