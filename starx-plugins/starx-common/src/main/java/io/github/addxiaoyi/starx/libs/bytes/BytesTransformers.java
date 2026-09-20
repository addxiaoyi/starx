/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.BytesTransformer;
import io.github.addxiaoyi.starx.libs.bytes.Util;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class BytesTransformers {
    private BytesTransformers() {
    }

    public static BytesTransformer shuffle() {
        return new ShuffleTransformer(new SecureRandom());
    }

    public static BytesTransformer shuffle(Random random) {
        return new ShuffleTransformer(random);
    }

    public static BytesTransformer sort() {
        return new SortTransformer();
    }

    public static BytesTransformer sortUnsigned() {
        return new SortTransformer(new SortTransformer.UnsignedByteComparator());
    }

    public static BytesTransformer sort(Comparator<Byte> comparator) {
        return new SortTransformer(comparator);
    }

    public static BytesTransformer checksumAppendCrc32() {
        return new ChecksumTransformer(new CRC32(), ChecksumTransformer.Mode.APPEND, 4);
    }

    public static BytesTransformer checksumCrc32() {
        return new ChecksumTransformer(new CRC32(), ChecksumTransformer.Mode.TRANSFORM, 4);
    }

    public static BytesTransformer checksum(Checksum checksum, ChecksumTransformer.Mode mode, int checksumLengthByte) {
        return new ChecksumTransformer(checksum, mode, checksumLengthByte);
    }

    public static BytesTransformer compressGzip() {
        return new GzipCompressor(true);
    }

    public static BytesTransformer decompressGzip() {
        return new GzipCompressor(false);
    }

    public static BytesTransformer hmacSha1(byte[] key) {
        return new HmacTransformer(key, "HmacSHA1");
    }

    public static BytesTransformer hmacSha256(byte[] key) {
        return new HmacTransformer(key, "HmacSHA256");
    }

    public static BytesTransformer hmac(byte[] key, String algorithmName) {
        return new HmacTransformer(key, algorithmName);
    }

    public static final class HmacTransformer
    implements BytesTransformer {
        static final String HMAC_SHA1 = "HmacSHA1";
        static final String HMAC_SHA256 = "HmacSHA256";
        private final byte[] secretKey;
        private final String macAlgorithmName;

        HmacTransformer(byte[] secretKey, String macAlgorithmName) {
            this.macAlgorithmName = macAlgorithmName;
            this.secretKey = secretKey;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            try {
                Mac mac = Mac.getInstance(this.macAlgorithmName);
                mac.init(new SecretKeySpec(this.secretKey, this.macAlgorithmName));
                return mac.doFinal(currentArray);
            }
            catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }
    }

    public static final class GzipCompressor
    implements BytesTransformer {
        private final boolean compress;

        GzipCompressor(boolean compress) {
            this.compress = compress;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            return this.compress ? this.compress(currentArray) : this.decompress(currentArray);
        }

        /*
         * Enabled aggressive block sorting
         * Enabled unnecessary exception pruning
         * Enabled aggressive exception aggregation
         */
        private byte[] decompress(byte[] compressedContent) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, compressedContent.length / 2));
            try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedContent));){
                int len;
                byte[] buffer = new byte[4096];
                while ((len = gzipInputStream.read(buffer)) > 0) {
                    bos.write(buffer, 0, len);
                }
                byte[] byArray = bos.toByteArray();
                return byArray;
            }
            catch (Exception e) {
                throw new IllegalStateException("could not decompress gzip", e);
            }
        }

        private byte[] compress(byte[] content) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(content.length);
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(bos);){
                gzipOutputStream.write(content);
            }
            catch (Exception e) {
                throw new IllegalStateException("could not compress gzip", e);
            }
            return bos.toByteArray();
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }
    }

    public static final class ChecksumTransformer
    implements BytesTransformer {
        private final Checksum checksum;
        private final Mode mode;
        private final int checksumLengthByte;

        ChecksumTransformer(Checksum checksum, Mode mode, int checksumLengthByte) {
            if (checksumLengthByte <= 0 || checksumLengthByte > 8) {
                throw new IllegalArgumentException("checksum length must be between 1 and 8 bytes");
            }
            Objects.requireNonNull(checksum, "checksum instance must not be null");
            this.checksum = checksum;
            this.mode = mode;
            this.checksumLengthByte = checksumLengthByte;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            this.checksum.update(currentArray, 0, currentArray.length);
            byte[] checksumBytes = Bytes.from(this.checksum.getValue()).resize(this.checksumLengthByte).array();
            if (this.mode == Mode.TRANSFORM) {
                return checksumBytes;
            }
            return Bytes.from(currentArray, checksumBytes).array();
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return false;
        }

        public static enum Mode {
            APPEND,
            TRANSFORM;

        }
    }

    public static final class SortTransformer
    implements BytesTransformer {
        private final Comparator comparator;

        SortTransformer() {
            this(null);
        }

        SortTransformer(Comparator<Byte> comparator) {
            this.comparator = comparator;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            if (this.comparator == null) {
                byte[] out = inPlace ? currentArray : Bytes.from(currentArray).array();
                Arrays.sort(out);
                return out;
            }
            Byte[] boxedArray = Bytes.wrap(currentArray).toBoxedArray();
            Arrays.sort(boxedArray, this.comparator);
            return Bytes.from(boxedArray).array();
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return this.comparator == null;
        }

        static final class UnsignedByteComparator
        implements Comparator<Byte> {
            UnsignedByteComparator() {
            }

            @Override
            public int compare(Byte o1, Byte o2) {
                int byteB;
                int byteA = o1 & 0xFF;
                if (byteA == (byteB = o2 & 0xFF)) {
                    return 0;
                }
                return byteA < byteB ? -1 : 1;
            }
        }
    }

    public static final class ShuffleTransformer
    implements BytesTransformer {
        private final Random random;

        ShuffleTransformer(Random random) {
            Objects.requireNonNull(random, "passed random must not be null");
            this.random = random;
        }

        @Override
        public byte[] transform(byte[] currentArray, boolean inPlace) {
            byte[] out = inPlace ? currentArray : Bytes.from(currentArray).array();
            Util.Byte.shuffle(out, this.random);
            return out;
        }

        @Override
        public boolean supportInPlaceTransformation() {
            return true;
        }
    }
}
