/*
 * Decompiled with CFR 0.152.
 */
package at.favre.lib.crypto.bcrypt;

import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.IllegalBCryptFormatException;
import at.favre.lib.crypto.bcrypt.Radix64Encoder;
import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public interface BCryptParser {
    public BCrypt.HashData parse(byte[] var1) throws IllegalBCryptFormatException;

    public static final class Default
    implements BCryptParser {
        private final Charset defaultCharset;
        private final Radix64Encoder encoder;

        Default(Radix64Encoder encoder, Charset defaultCharset) {
            this.defaultCharset = defaultCharset;
            this.encoder = encoder;
        }

        @Override
        public BCrypt.HashData parse(byte[] bcryptHash) throws IllegalBCryptFormatException {
            int parsedCostFactor;
            if (bcryptHash == null || bcryptHash.length == 0) {
                throw new IllegalArgumentException("must provide non-null, non-empty hash");
            }
            if (bcryptHash.length < 7) {
                throw new IllegalBCryptFormatException("hash prefix meta must be at least 7 bytes long e.g. '$2a$10$'");
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(bcryptHash);
            if (byteBuffer.get() != 36) {
                throw new IllegalBCryptFormatException("hash must start with " + Bytes.from((byte)36).encodeUtf8());
            }
            BCrypt.Version usedVersion = null;
            for (BCrypt.Version versionToTest : BCrypt.Version.SUPPORTED_VERSIONS) {
                for (int i = 0; i < versionToTest.versionIdentifier.length; ++i) {
                    if (byteBuffer.get() != versionToTest.versionIdentifier[i]) {
                        byteBuffer.position(byteBuffer.position() - (i + 1));
                        break;
                    }
                    if (i != versionToTest.versionIdentifier.length - 1) continue;
                    usedVersion = versionToTest;
                }
                if (usedVersion == null) continue;
                break;
            }
            if (usedVersion == null) {
                throw new IllegalBCryptFormatException("unknown bcrypt version");
            }
            if (byteBuffer.get() != 36) {
                throw new IllegalBCryptFormatException("expected separator " + Bytes.from((byte)36).encodeUtf8() + " after version identifier and before cost factor");
            }
            byte[] costBytes = new byte[]{byteBuffer.get(), byteBuffer.get()};
            try {
                parsedCostFactor = Integer.parseInt(new String(costBytes, this.defaultCharset));
            }
            catch (NumberFormatException e) {
                throw new IllegalBCryptFormatException("cannot parse cost factor '" + new String(costBytes, this.defaultCharset) + "'");
            }
            if (byteBuffer.get() != 36) {
                throw new IllegalBCryptFormatException("expected separator " + Bytes.from((byte)36).encodeUtf8() + " after cost factor");
            }
            if (bcryptHash.length != 60) {
                throw new IllegalBCryptFormatException("hash expected to be exactly 60 bytes");
            }
            byte[] salt = new byte[22];
            byte[] hash = new byte[31];
            byteBuffer.get(salt);
            byteBuffer.get(hash);
            return new BCrypt.HashData(parsedCostFactor, usedVersion, this.encoder.decode(salt), this.encoder.decode(hash));
        }
    }
}
