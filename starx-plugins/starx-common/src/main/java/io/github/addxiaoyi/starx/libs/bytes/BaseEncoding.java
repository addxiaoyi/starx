/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.BinaryToTextEncoding;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

final class BaseEncoding
implements BinaryToTextEncoding.EncoderDecoder {
    private static final char ASCII_MAX = '\u007f';
    static final Alphabet BASE32_RFC4848 = new Alphabet("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray());
    static final char BASE32_RFC4848_PADDING = '=';
    private final Alphabet alphabet;
    private final Character paddingChar;

    public BaseEncoding(Alphabet alphabet, Character paddingChar) {
        this.alphabet = Objects.requireNonNull(alphabet);
        this.paddingChar = paddingChar;
    }

    private int maxEncodedSize(int bytes) {
        return this.alphabet.charsPerChunk * BaseEncoding.divide(bytes, this.alphabet.bytesPerChunk);
    }

    @Override
    public String encode(byte[] array, ByteOrder byteOrder) {
        return this.encode(array, 0, array.length);
    }

    private String encode(byte[] bytes, int off, int len) {
        StringBuilder result = new StringBuilder(this.maxEncodedSize(len));
        try {
            this.encodeTo(result, bytes, off, len);
        }
        catch (IOException impossible) {
            throw new AssertionError((Object)impossible);
        }
        return result.toString();
    }

    private void encodeTo(Appendable target, byte[] bytes, int off, int len) throws IOException {
        Objects.requireNonNull(target);
        for (int i = 0; i < len; i += this.alphabet.bytesPerChunk) {
            this.encodeChunkTo(target, bytes, off + i, Math.min(this.alphabet.bytesPerChunk, len - i));
        }
    }

    private void encodeChunkTo(Appendable target, byte[] bytes, int off, int len) throws IOException {
        int bitsProcessed;
        Objects.requireNonNull(target);
        long bitBuffer = 0L;
        for (int i = 0; i < len; ++i) {
            bitBuffer |= (long)(bytes[off + i] & 0xFF);
            bitBuffer <<= 8;
        }
        int bitOffset = (len + 1) * 8 - this.alphabet.bitsPerChar;
        for (bitsProcessed = 0; bitsProcessed < len * 8; bitsProcessed += this.alphabet.bitsPerChar) {
            int charIndex = (int)(bitBuffer >>> bitOffset - bitsProcessed) & this.alphabet.mask;
            target.append(this.alphabet.encode(charIndex));
        }
        if (this.paddingChar != null) {
            while (bitsProcessed < this.alphabet.bytesPerChunk * 8) {
                target.append(this.paddingChar.charValue());
                bitsProcessed += this.alphabet.bitsPerChar;
            }
        }
    }

    private int maxDecodedSize(int chars) {
        return (int)(((long)this.alphabet.bitsPerChar * (long)chars + 7L) / 8L);
    }

    private String trimTrailingPadding(CharSequence chars) {
        int l;
        Objects.requireNonNull(chars);
        if (this.paddingChar == null) {
            return chars.toString();
        }
        for (l = chars.length() - 1; l >= 0 && chars.charAt(l) == this.paddingChar.charValue(); --l) {
        }
        return chars.subSequence(0, l + 1).toString();
    }

    @Override
    public byte[] decode(CharSequence encoded) {
        encoded = this.trimTrailingPadding(encoded);
        byte[] tmp = new byte[this.maxDecodedSize(encoded.length())];
        int len = this.decodeTo(tmp, encoded);
        return BaseEncoding.extract(tmp, len);
    }

    private static byte[] extract(byte[] result, int length) {
        if (length == result.length) {
            return result;
        }
        byte[] trunc = new byte[length];
        System.arraycopy(result, 0, trunc, 0, length);
        return trunc;
    }

    private int decodeTo(byte[] target, CharSequence chars) {
        Objects.requireNonNull(target);
        chars = this.trimTrailingPadding(chars);
        int bytesWritten = 0;
        for (int charIdx = 0; charIdx < chars.length(); charIdx += this.alphabet.charsPerChunk) {
            long chunk = 0L;
            int charsProcessed = 0;
            for (int i = 0; i < this.alphabet.charsPerChunk; ++i) {
                chunk <<= this.alphabet.bitsPerChar;
                if (charIdx + i >= chars.length()) continue;
                chunk |= (long)this.alphabet.decode(chars.charAt(charIdx + charsProcessed++));
            }
            int minOffset = this.alphabet.bytesPerChunk * 8 - charsProcessed * this.alphabet.bitsPerChar;
            for (int offset = (this.alphabet.bytesPerChunk - 1) * 8; offset >= minOffset; offset -= 8) {
                target[bytesWritten++] = (byte)(chunk >>> offset & 0xFFL);
            }
        }
        return bytesWritten;
    }

    private static int divide(int p, int q) {
        int div = p / q;
        int rem = p - q * div;
        if (rem == 0) {
            return div;
        }
        int signum = 1 | (p ^ q) >> 31;
        return signum > 0 ? div + signum : div;
    }

    private static int log2(int x) {
        return 31 - Integer.numberOfLeadingZeros(x);
    }

    static final class Alphabet {
        private final char[] chars;
        final int mask;
        final int bitsPerChar;
        final int charsPerChunk;
        final int bytesPerChunk;
        private final byte[] decodabet;

        Alphabet(char[] chars) {
            this.chars = Objects.requireNonNull(chars);
            this.bitsPerChar = BaseEncoding.log2(chars.length);
            int gcd = Math.min(8, Integer.lowestOneBit(this.bitsPerChar));
            this.charsPerChunk = 8 / gcd;
            this.bytesPerChunk = this.bitsPerChar / gcd;
            this.mask = chars.length - 1;
            byte[] decodabet = new byte[128];
            Arrays.fill(decodabet, (byte)-1);
            for (int i = 0; i < chars.length; ++i) {
                char c = chars[i];
                decodabet[c] = (byte)i;
            }
            this.decodabet = decodabet;
        }

        char encode(int bits) {
            return this.chars[bits];
        }

        int decode(char ch) {
            return this.decodabet[ch];
        }
    }
}
