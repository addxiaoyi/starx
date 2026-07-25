/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.libs.bytes;

import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

final class Util {
    private Util() {
    }

    static final class BytesIterator
    implements Iterator<java.lang.Byte> {
        private final byte[] array;
        private int cursor = 0;

        BytesIterator(byte[] array) {
            this.array = array;
        }

        @Override
        public boolean hasNext() {
            return this.cursor != this.array.length;
        }

        @Override
        public java.lang.Byte next() {
            try {
                int i = this.cursor;
                java.lang.Byte next = this.array[i];
                this.cursor = i + 1;
                return next;
            }
            catch (IndexOutOfBoundsException e) {
                throw new NoSuchElementException();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("The Bytes iterator does not support removing");
        }
    }

    static final class File {
        private static final int BUF_SIZE = 4096;

        private File() {
        }

        static byte[] readFromStream(InputStream inputStream, int maxLengthToRead) {
            boolean readWholeStream = maxLengthToRead == -1;
            try {
                int r;
                ByteArrayOutputStream out = new ByteArrayOutputStream(readWholeStream ? 32 : maxLengthToRead);
                byte[] buf = new byte[]{};
                for (int remaining = maxLengthToRead; readWholeStream || remaining > 0; remaining -= r) {
                    int bufSize = Math.min(4096, readWholeStream ? 4096 : remaining);
                    if (buf.length != bufSize) {
                        buf = new byte[bufSize];
                    }
                    if ((r = inputStream.read(buf)) == -1) break;
                    out.write(buf, 0, r);
                }
                return out.toByteArray();
            }
            catch (Exception e) {
                throw new IllegalStateException("could not read from input stream", e);
            }
        }

        static byte[] readFromDataInput(DataInput dataInput, int length) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length);
            try {
                int remaining = length;
                for (int i = 0; i < length; ++i) {
                    byte[] buf = new byte[Math.min(remaining, 4096)];
                    dataInput.readFully(buf);
                    out.write(buf);
                    remaining -= buf.length;
                }
                return out.toByteArray();
            }
            catch (Exception e) {
                throw new IllegalStateException("could not read from data input", e);
            }
        }

        static byte[] readFromFile(java.io.File file) {
            Validation.checkFileExists(file);
            try {
                return Files.readAllBytes(file.toPath());
            }
            catch (IOException e) {
                throw new IllegalStateException("could not read from file", e);
            }
        }

        /*
         * Enabled aggressive block sorting
         * Enabled unnecessary exception pruning
         * Enabled aggressive exception aggregation
         */
        static byte[] readFromFile(java.io.File file, int offset, int length) {
            Validation.checkFileExists(file);
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");){
                raf.seek(offset);
                byte[] byArray = File.readFromDataInput(raf, length);
                return byArray;
            }
            catch (Exception e) {
                throw new IllegalStateException("could not read from random access file", e);
            }
        }
    }

    static final class Validation {
        private Validation() {
        }

        static void checkIndexBounds(int length, int index, int primitiveLength, String type) {
            if (index < 0 || index + primitiveLength > length) {
                throw new IndexOutOfBoundsException("cannot get " + type + " from index out of bounds: " + index);
            }
        }

        static void checkExactLength(int length, int expectedLength, String type) {
            if (length != expectedLength) {
                throw new IllegalArgumentException("cannot convert to " + type + " if length != " + expectedLength + " bytes (was " + length + ")");
            }
        }

        static void checkModLength(int length, int modFactor, String errorSubject) {
            if (length % modFactor != 0) {
                throw new IllegalArgumentException("Illegal length for " + errorSubject + ". Byte array length must be multiple of " + modFactor + ", length was " + length);
            }
        }

        private static void checkFileExists(java.io.File file) {
            if (file == null || !file.exists() || !file.isFile()) {
                throw new IllegalArgumentException("file must not be null, has to exist and must be a file (not a directory) " + file);
            }
        }
    }

    static final class Obj {
        private Obj() {
        }

        static boolean equals(byte[] obj, java.lang.Byte[] anotherArray) {
            if (anotherArray == null) {
                return false;
            }
            if (obj.length != anotherArray.length) {
                return false;
            }
            for (int i = 0; i < obj.length; ++i) {
                if (anotherArray[i] != null && obj[i] == anotherArray[i]) continue;
                return false;
            }
            return true;
        }

        static int hashCode(byte[] byteArray, ByteOrder byteOrder) {
            int result = Arrays.hashCode(byteArray);
            result = 31 * result + (byteOrder != null ? byteOrder.hashCode() : 0);
            return result;
        }

        static String toString(Bytes bytes) {
            String preview = bytes.isEmpty() ? "" : (bytes.length() > 8 ? "(0x" + bytes.copy(0, 4).encodeHex() + "..." + bytes.copy(bytes.length() - 4, 4).encodeHex() + ")" : "(0x" + bytes.encodeHex() + ")");
            return bytes.length() + " " + (bytes.length() == 1 ? "byte" : "bytes") + " " + preview;
        }
    }

    static final class Converter {
        private Converter() {
        }

        static byte[] toArray(Collection<java.lang.Byte> collection) {
            int len = collection.size();
            byte[] array = new byte[len];
            int i = 0;
            for (java.lang.Byte b : collection) {
                array[i] = b;
                ++i;
            }
            return array;
        }

        static java.lang.Byte[] toBoxedArray(byte[] array) {
            java.lang.Byte[] objectArray = new java.lang.Byte[array.length];
            for (int i = 0; i < array.length; ++i) {
                objectArray[i] = array[i];
            }
            return objectArray;
        }

        static List<java.lang.Byte> toList(byte[] array) {
            ArrayList<java.lang.Byte> list = new ArrayList<java.lang.Byte>(array.length);
            for (byte b : array) {
                list.add(b);
            }
            return list;
        }

        static byte[] toPrimitiveArray(java.lang.Byte[] objectArray) {
            byte[] primitivesArray = new byte[objectArray.length];
            for (int i = 0; i < objectArray.length; ++i) {
                primitivesArray[i] = objectArray[i];
            }
            return primitivesArray;
        }

        static byte[] toByteArray(int[] intArray) {
            byte[] primitivesArray = new byte[intArray.length * 4];
            ByteBuffer buffer = ByteBuffer.allocate(4);
            for (int i = 0; i < intArray.length; ++i) {
                buffer.clear();
                byte[] intBytes = buffer.putInt(intArray[i]).array();
                System.arraycopy(intBytes, 0, primitivesArray, i * 4, intBytes.length);
            }
            return primitivesArray;
        }

        static byte[] toByteArray(float[] floatArray) {
            byte[] primitivesArray = new byte[floatArray.length * 4];
            ByteBuffer buffer = ByteBuffer.allocate(4);
            for (int i = 0; i < floatArray.length; ++i) {
                buffer.clear();
                byte[] floatBytes = buffer.putFloat(floatArray[i]).array();
                System.arraycopy(floatBytes, 0, primitivesArray, i * 4, floatBytes.length);
            }
            return primitivesArray;
        }

        static byte[] toByteArray(long[] longArray) {
            byte[] primitivesArray = new byte[longArray.length * 8];
            ByteBuffer buffer = ByteBuffer.allocate(8);
            for (int i = 0; i < longArray.length; ++i) {
                buffer.clear();
                byte[] longBytes = buffer.putLong(longArray[i]).array();
                System.arraycopy(longBytes, 0, primitivesArray, i * 8, longBytes.length);
            }
            return primitivesArray;
        }

        static byte[] toByteArray(double[] doubleArray) {
            byte[] primitivesArray = new byte[doubleArray.length * 8];
            ByteBuffer buffer = ByteBuffer.allocate(8);
            for (int i = 0; i < doubleArray.length; ++i) {
                buffer.clear();
                byte[] doubleBytes = buffer.putDouble(doubleArray[i]).array();
                System.arraycopy(doubleBytes, 0, primitivesArray, i * 8, doubleBytes.length);
            }
            return primitivesArray;
        }

        static byte[] charToByteArray(char[] charArray, Charset charset, int offset, int length) {
            ByteBuffer bb;
            if (offset < 0 || offset > charArray.length) {
                throw new IllegalArgumentException("offset must be gt 0 and smaller than array length");
            }
            if (length < 0 || length > charArray.length) {
                throw new IllegalArgumentException("length must be at least 1 and less than array length");
            }
            if (offset + length > charArray.length) {
                throw new IllegalArgumentException("length + offset must be smaller than array length");
            }
            if (length == 0) {
                return new byte[0];
            }
            CharBuffer charBuffer = CharBuffer.wrap(charArray);
            if (offset != 0 || length != charBuffer.remaining()) {
                charBuffer = charBuffer.subSequence(offset, offset + length);
            }
            if ((bb = charset.encode(charBuffer)).capacity() != bb.limit()) {
                byte[] bytes = new byte[bb.remaining()];
                bb.get(bytes);
                return bytes;
            }
            return bb.array();
        }

        static char[] byteToCharArray(byte[] bytes, Charset charset, ByteOrder byteOrder) {
            Objects.requireNonNull(bytes, "bytes must not be null");
            Objects.requireNonNull(charset, "charset must not be null");
            try {
                CharBuffer charBuffer = charset.newDecoder().decode(ByteBuffer.wrap(bytes).order(byteOrder));
                if (charBuffer.capacity() != charBuffer.limit()) {
                    char[] compacted = new char[charBuffer.remaining()];
                    charBuffer.get(compacted);
                    return compacted;
                }
                return charBuffer.array();
            }
            catch (CharacterCodingException e) {
                throw new IllegalStateException(e);
            }
        }

        static int[] toIntArray(byte[] bytes, ByteOrder byteOrder) {
            IntBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder).asIntBuffer();
            int[] array = new int[buffer.remaining()];
            buffer.get(array);
            return array;
        }

        static long[] toLongArray(byte[] bytes, ByteOrder byteOrder) {
            LongBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder).asLongBuffer();
            long[] array = new long[buffer.remaining()];
            buffer.get(array);
            return array;
        }

        static float[] toFloatArray(byte[] bytes, ByteOrder byteOrder) {
            FloatBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder).asFloatBuffer();
            float[] array = new float[buffer.remaining()];
            buffer.get(array);
            return array;
        }

        static double[] toDoubleArray(byte[] bytes, ByteOrder byteOrder) {
            DoubleBuffer buffer = ByteBuffer.wrap(bytes).order(byteOrder).asDoubleBuffer();
            double[] array = new double[buffer.remaining()];
            buffer.get(array);
            return array;
        }

        static ByteBuffer toBytesFromUUID(UUID uuid) {
            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.putLong(uuid.getMostSignificantBits());
            bb.putLong(uuid.getLeastSignificantBits());
            return bb;
        }
    }

    static final class Byte {
        private Byte() {
        }

        static byte[] concat(byte[] ... arrays) {
            int length = 0;
            for (byte[] array : arrays) {
                length += array.length;
            }
            byte[] result = new byte[length];
            int pos = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, result, pos, array.length);
                pos += array.length;
            }
            return result;
        }

        static byte[] concatVararg(byte firstByte, byte[] moreBytes) {
            if (moreBytes == null) {
                return new byte[]{firstByte};
            }
            byte[] first = new byte[]{firstByte};
            byte[] result = new byte[first.length + moreBytes.length];
            System.arraycopy(first, 0, result, 0, first.length);
            System.arraycopy(moreBytes, 0, result, first.length, moreBytes.length);
            return result;
        }

        static int indexOf(byte[] array, byte[] target, int start, int end) {
            Objects.requireNonNull(array, "array must not be null");
            Objects.requireNonNull(target, "target must not be null");
            if (target.length == 0 || start < 0) {
                return -1;
            }
            block0: for (int i = start; i < Math.min(end, array.length - target.length + 1); ++i) {
                for (int j = 0; j < target.length; ++j) {
                    if (array[i + j] != target[j]) continue block0;
                }
                return i;
            }
            return -1;
        }

        static int lastIndexOf(byte[] array, byte target, int start, int end) {
            for (int i = end - 1; i >= start; --i) {
                if (array[i] != target) continue;
                return i;
            }
            return -1;
        }

        static int countByte(byte[] array, byte target) {
            int count = 0;
            for (byte b : array) {
                if (b != target) continue;
                ++count;
            }
            return count;
        }

        static int countByteArray(byte[] array, byte[] pattern) {
            Objects.requireNonNull(pattern, "pattern must not be null");
            if (pattern.length == 0 || array.length == 0) {
                return 0;
            }
            int count = 0;
            block0: for (int i = 0; i < array.length - pattern.length + 1; ++i) {
                for (int j = 0; j < pattern.length; ++j) {
                    if (array[i + j] != pattern[j]) continue block0;
                }
                ++count;
            }
            return count;
        }

        static void shuffle(byte[] array, Random random) {
            for (int i = array.length - 1; i > 0; --i) {
                int index = random.nextInt(i + 1);
                byte a = array[index];
                array[index] = array[i];
                array[i] = a;
            }
        }

        static void reverse(byte[] array, int fromIndex, int toIndex) {
            Objects.requireNonNull(array);
            int i = fromIndex;
            for (int j = toIndex - 1; i < j; ++i, --j) {
                byte tmp = array[i];
                array[i] = array[j];
                array[j] = tmp;
            }
        }

        static byte[] shiftLeft(byte[] byteArray, int shiftBitCount, ByteOrder byteOrder) {
            int shiftMod = shiftBitCount % 8;
            byte carryMask = (byte)((1 << shiftMod) - 1);
            int offsetBytes = shiftBitCount / 8;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                for (int i = 0; i < byteArray.length; ++i) {
                    int sourceIndex = i + offsetBytes;
                    if (sourceIndex >= byteArray.length) {
                        byteArray[i] = 0;
                        continue;
                    }
                    byte src = byteArray[sourceIndex];
                    byte dst = (byte)(src << shiftMod);
                    if (sourceIndex + 1 < byteArray.length) {
                        dst = (byte)(dst | byteArray[sourceIndex + 1] >>> 8 - shiftMod & carryMask);
                    }
                    byteArray[i] = dst;
                }
            } else {
                for (int i = byteArray.length - 1; i >= 0; --i) {
                    int sourceIndex = i - offsetBytes;
                    if (sourceIndex < 0) {
                        byteArray[i] = 0;
                        continue;
                    }
                    byte src = byteArray[sourceIndex];
                    byte dst = (byte)(src << shiftMod);
                    if (sourceIndex - 1 >= 0) {
                        dst = (byte)(dst | byteArray[sourceIndex - 1] >>> 8 - shiftMod & carryMask);
                    }
                    byteArray[i] = dst;
                }
            }
            return byteArray;
        }

        static byte[] shiftRight(byte[] byteArray, int shiftBitCount, ByteOrder byteOrder) {
            int shiftMod = shiftBitCount % 8;
            byte carryMask = (byte)(255 << 8 - shiftMod);
            int offsetBytes = shiftBitCount / 8;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                for (int i = byteArray.length - 1; i >= 0; --i) {
                    int sourceIndex = i - offsetBytes;
                    if (sourceIndex < 0) {
                        byteArray[i] = 0;
                        continue;
                    }
                    byte src = byteArray[sourceIndex];
                    byte dst = (byte)((0xFF & src) >>> shiftMod);
                    if (sourceIndex - 1 >= 0) {
                        dst = (byte)(dst | byteArray[sourceIndex - 1] << 8 - shiftMod & carryMask);
                    }
                    byteArray[i] = dst;
                }
            } else {
                for (int i = 0; i < byteArray.length; ++i) {
                    int sourceIndex = i + offsetBytes;
                    if (sourceIndex >= byteArray.length) {
                        byteArray[i] = 0;
                        continue;
                    }
                    byte src = byteArray[sourceIndex];
                    byte dst = (byte)((0xFF & src) >>> shiftMod);
                    if (sourceIndex + 1 < byteArray.length) {
                        dst = (byte)(dst | byteArray[sourceIndex + 1] << 8 - shiftMod & carryMask);
                    }
                    byteArray[i] = dst;
                }
            }
            return byteArray;
        }

        static boolean constantTimeEquals(byte[] array, byte[] anotherArray) {
            if (anotherArray == null || array.length != anotherArray.length) {
                return false;
            }
            int result = 0;
            for (int i = 0; i < array.length; ++i) {
                result |= array[i] ^ anotherArray[i];
            }
            return result == 0;
        }

        static double entropy(byte[] array) {
            int[] buffer = new int[256];
            Arrays.fill(buffer, -1);
            for (byte element : array) {
                int unsigned = 0xFF & element;
                if (buffer[unsigned] == -1) {
                    buffer[unsigned] = 0;
                }
                int n = unsigned;
                buffer[n] = buffer[n] + 1;
            }
            double entropy = 0.0;
            for (int count : buffer) {
                if (count == -1) continue;
                double prob = (double)count / (double)array.length;
                entropy -= prob * (Math.log(prob) / Math.log(2.0));
            }
            return entropy;
        }
    }
}
