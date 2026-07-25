/*
 * Decompiled with CFR 0.152.
 */
package at.favre.lib.crypto.bcrypt;

import at.favre.lib.crypto.bcrypt.BCryptFormatter;
import at.favre.lib.crypto.bcrypt.BCryptOpenBSDProtocol;
import at.favre.lib.crypto.bcrypt.BCryptParser;
import at.favre.lib.crypto.bcrypt.IllegalBCryptFormatException;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategy;
import at.favre.lib.crypto.bcrypt.Radix64Encoder;
import io.github.addxiaoyi.starx.libs.bytes.Bytes;
import io.github.addxiaoyi.starx.libs.bytes.BytesTransformer;
import io.github.addxiaoyi.starx.libs.bytes.BytesValidators;
import io.github.addxiaoyi.starx.libs.bytes.MutableBytes;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BCrypt {
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    public static final int SALT_LENGTH = 16;
    public static final int MIN_COST = 4;
    public static final int MAX_COST = 31;
    static final byte MAJOR_VERSION = 50;
    static final int HASH_OUT_LENGTH = 23;
    static final byte SEPARATOR = 36;

    private BCrypt() {
    }

    public static Hasher withDefaults() {
        return new Hasher(Version.VERSION_2A, new SecureRandom(), LongPasswordStrategies.strict(Version.VERSION_2A));
    }

    public static Hasher with(Version version) {
        return new Hasher(version, new SecureRandom(), LongPasswordStrategies.strict(version));
    }

    public static Hasher with(SecureRandom secureRandom) {
        return new Hasher(Version.VERSION_2A, secureRandom, LongPasswordStrategies.strict(Version.VERSION_2A));
    }

    public static Hasher with(LongPasswordStrategy longPasswordStrategy) {
        return new Hasher(Version.VERSION_2A, new SecureRandom(), longPasswordStrategy);
    }

    public static Hasher with(Version version, LongPasswordStrategy longPasswordStrategy) {
        return new Hasher(version, new SecureRandom(), longPasswordStrategy);
    }

    public static Hasher with(Version version, SecureRandom secureRandom, LongPasswordStrategy longPasswordStrategy) {
        return new Hasher(version, secureRandom, longPasswordStrategy);
    }

    public static Verifyer verifyer() {
        return BCrypt.verifyer(null, null);
    }

    public static Verifyer verifyer(Version version) {
        return new Verifyer(version, LongPasswordStrategies.strict(version));
    }

    public static Verifyer verifyer(Version version, LongPasswordStrategy longPasswordStrategy) {
        return new Verifyer(version, longPasswordStrategy);
    }

    static /* synthetic */ Charset access$200() {
        return DEFAULT_CHARSET;
    }

    public static final class Version {
        private static final BCryptFormatter DEFAULT_FORMATTER = new BCryptFormatter.Default(new Radix64Encoder.Default(), BCrypt.access$200());
        private static final BCryptParser DEFAULT_PARSER = new BCryptParser.Default(new Radix64Encoder.Default(), BCrypt.access$200());
        public static final int MAX_PW_LENGTH_BYTE = 72;
        @Deprecated
        public static final int DEFAULT_MAX_PW_LENGTH_BYTE = 71;
        public static final Version VERSION_2A = new Version(new byte[]{50, 97}, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final Version VERSION_2B = new Version(new byte[]{50, 98}, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final Version VERSION_2X = new Version(new byte[]{50, 120}, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final Version VERSION_2Y = new Version(new byte[]{50, 121}, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final Version VERSION_2Y_NO_NULL_TERMINATOR = new Version(new byte[]{50, 121}, true, false, 72, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final Version VERSION_BC = new Version(new byte[]{50, 99}, false, false, 72, DEFAULT_FORMATTER, DEFAULT_PARSER);
        public static final List<Version> SUPPORTED_VERSIONS = Collections.unmodifiableList(Arrays.asList(VERSION_2A, VERSION_2B, VERSION_2X, VERSION_2Y));
        public final byte[] versionIdentifier;
        public final boolean useOnly23bytesForHash;
        public final boolean appendNullTerminator;
        public final int allowedMaxPwLength;
        public final BCryptFormatter formatter;
        public final BCryptParser parser;

        private Version(byte[] versionIdentifier, BCryptFormatter formatter, BCryptParser parser) {
            this(versionIdentifier, true, true, 72, formatter, parser);
        }

        public Version(byte[] versionIdentifier, boolean useOnly23bytesForHash, boolean appendNullTerminator, int allowedMaxPwLength, BCryptFormatter formatter, BCryptParser parser) {
            this.versionIdentifier = versionIdentifier;
            this.useOnly23bytesForHash = useOnly23bytesForHash;
            this.appendNullTerminator = appendNullTerminator;
            this.allowedMaxPwLength = allowedMaxPwLength;
            this.formatter = formatter;
            this.parser = parser;
            if (allowedMaxPwLength > 72) {
                throw new IllegalArgumentException("allowed max pw length cannot be gt 72");
            }
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || this.getClass() != o.getClass()) {
                return false;
            }
            Version version = (Version)o;
            return this.useOnly23bytesForHash == version.useOnly23bytesForHash && this.appendNullTerminator == version.appendNullTerminator && this.allowedMaxPwLength == version.allowedMaxPwLength && Arrays.equals(this.versionIdentifier, version.versionIdentifier);
        }

        public int hashCode() {
            int result = Objects.hash(this.useOnly23bytesForHash, this.appendNullTerminator, this.allowedMaxPwLength);
            result = 31 * result + Arrays.hashCode(this.versionIdentifier);
            return result;
        }

        public String toString() {
            return "$" + new String(this.versionIdentifier) + "$";
        }
    }

    public static final class Result {
        public final HashData details;
        public final boolean validFormat;
        public final boolean verified;
        public final String formatErrorMessage;

        Result(IllegalBCryptFormatException e) {
            this(null, false, false, e.getMessage());
        }

        Result(HashData details, boolean verified) {
            this(details, true, verified, null);
        }

        private Result(HashData details, boolean validFormat, boolean verified, String formatErrorMessage) {
            this.details = details;
            this.validFormat = validFormat;
            this.verified = verified;
            this.formatErrorMessage = formatErrorMessage;
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || this.getClass() != o.getClass()) {
                return false;
            }
            Result result = (Result)o;
            return this.validFormat == result.validFormat && this.verified == result.verified && Objects.equals(this.details, result.details) && Objects.equals(this.formatErrorMessage, result.formatErrorMessage);
        }

        public int hashCode() {
            return Objects.hash(this.details, this.validFormat, this.verified, this.formatErrorMessage);
        }

        public String toString() {
            return "Result{details=" + this.details + ", validFormat=" + this.validFormat + ", verified=" + this.verified + ", formatErrorMessage='" + this.formatErrorMessage + '\'' + '}';
        }
    }

    public static final class Verifyer {
        private final Charset defaultCharset = BCrypt.access$200();
        private final LongPasswordStrategy longPasswordStrategy;
        private final Version version;

        private Verifyer(Version version, LongPasswordStrategy longPasswordStrategy) {
            this.version = version;
            this.longPasswordStrategy = longPasswordStrategy;
        }

        public Result verifyStrict(byte[] password, byte[] bcryptHash) {
            return this.innerVerifyBytes(password, bcryptHash, true);
        }

        public Result verify(byte[] password, byte[] bcryptHash) {
            return this.innerVerifyBytes(password, bcryptHash, false);
        }

        public Result verifyStrict(char[] password, char[] bcryptHash) {
            return this.innerVerifyChar(password, bcryptHash, true);
        }

        public Result verify(char[] password, char[] bcryptHash) {
            return this.innerVerifyChar(password, bcryptHash, false);
        }

        public Result verify(char[] password, CharSequence bcryptHash) {
            return this.innerVerifyChar(password, Verifyer.toCharArray(bcryptHash), false);
        }

        public Result verify(char[] password, byte[] bcryptHash) {
            try (MutableBytes pw = Bytes.from(password, this.defaultCharset).mutable();){
                Result result = this.innerVerifyBytes(pw.array(), bcryptHash, false);
                return result;
            }
        }

        private static char[] toCharArray(CharSequence charSequence) {
            if (charSequence instanceof String) {
                return charSequence.toString().toCharArray();
            }
            char[] buffer = new char[charSequence.length()];
            for (int i = 0; i < charSequence.length(); ++i) {
                buffer[i] = charSequence.charAt(i);
            }
            return buffer;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        private Result innerVerifyChar(char[] password, char[] bcryptHash, boolean strict) {
            Result result;
            byte[] passwordBytes = null;
            byte[] bcryptHashBytes = null;
            try {
                passwordBytes = Bytes.from(password, this.defaultCharset).array();
                bcryptHashBytes = Bytes.from(bcryptHash, this.defaultCharset).array();
                result = this.innerVerifyBytes(passwordBytes, bcryptHashBytes, strict);
                Bytes.wrapNullSafe(passwordBytes).mutable().secureWipe();
            }
            catch (Throwable throwable) {
                Bytes.wrapNullSafe(passwordBytes).mutable().secureWipe();
                Bytes.wrapNullSafe(bcryptHashBytes).mutable().secureWipe();
                throw throwable;
            }
            Bytes.wrapNullSafe(bcryptHashBytes).mutable().secureWipe();
            return result;
        }

        private Result innerVerifyBytes(byte[] password, byte[] bcryptHash, boolean strict) {
            Objects.requireNonNull(bcryptHash);
            try {
                Version usedVersion;
                HashData hashData;
                if (this.version == null) {
                    hashData = Version.VERSION_2A.parser.parse(bcryptHash);
                    usedVersion = hashData.version;
                } else {
                    usedVersion = this.version;
                    hashData = usedVersion.parser.parse(bcryptHash);
                }
                if (strict) {
                    if (this.version == null) {
                        throw new IllegalArgumentException("Using strict requires to define a Version. Try 'BCrypt.verifier(Version.VERSION_2A)'.");
                    }
                    if (hashData.version != this.version) {
                        return new Result(hashData, false);
                    }
                }
                return Verifyer.verifyBCrypt(usedVersion, this.determinePasswordStrategy(usedVersion), password, hashData.cost, hashData.rawSalt, hashData.rawHash);
            }
            catch (IllegalBCryptFormatException e) {
                return new Result(e);
            }
        }

        private LongPasswordStrategy determinePasswordStrategy(Version usedVersion) {
            LongPasswordStrategy usedLongPasswordStrategy = this.longPasswordStrategy == null ? LongPasswordStrategies.strict(usedVersion) : this.longPasswordStrategy;
            return usedLongPasswordStrategy;
        }

        public Result verify(byte[] password, HashData bcryptHashData) {
            return this.verify(password, bcryptHashData.cost, bcryptHashData.rawSalt, bcryptHashData.rawHash);
        }

        public Result verify(byte[] password, int cost, byte[] salt, byte[] rawBcryptHash23Bytes) {
            Version usedVersion = this.version == null ? Version.VERSION_2A : this.version;
            return Verifyer.verifyBCrypt(usedVersion, this.determinePasswordStrategy(usedVersion), password, cost, salt, rawBcryptHash23Bytes);
        }

        private static Result verifyBCrypt(Version version, LongPasswordStrategy longPasswordStrategy, byte[] password, int cost, byte[] salt, byte[] rawBcryptHash23Bytes) {
            HashData hashData = BCrypt.with(Objects.requireNonNull(version), Objects.requireNonNull(longPasswordStrategy)).hashRaw(cost, Objects.requireNonNull(salt), Objects.requireNonNull(password));
            return new Result(hashData, Bytes.wrap(hashData.rawHash).equalsConstantTime(Objects.requireNonNull(rawBcryptHash23Bytes)));
        }
    }

    public static final class HashData {
        public final int cost;
        public final Version version;
        public final byte[] rawSalt;
        public final byte[] rawHash;

        public HashData(int cost, Version version, byte[] rawSalt, byte[] rawHash) {
            Objects.requireNonNull(rawHash);
            Objects.requireNonNull(rawSalt);
            Objects.requireNonNull(version);
            if (!Bytes.wrap(rawSalt).validate(BytesValidators.exactLength(16)) || !Bytes.wrap(rawHash).validate(BytesValidators.or(BytesValidators.exactLength(23), BytesValidators.exactLength(24)))) {
                throw new IllegalArgumentException("salt must be exactly 16 bytes and hash 23 bytes long");
            }
            this.cost = cost;
            this.version = version;
            this.rawSalt = rawSalt;
            this.rawHash = rawHash;
        }

        public void wipe() {
            Bytes.wrapNullSafe(this.rawSalt).mutable().secureWipe();
            Bytes.wrapNullSafe(this.rawHash).mutable().secureWipe();
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || this.getClass() != o.getClass()) {
                return false;
            }
            HashData hashData = (HashData)o;
            return this.cost == hashData.cost && this.version == hashData.version && Bytes.wrap(this.rawSalt).equalsConstantTime(hashData.rawSalt) && Bytes.wrap(this.rawHash).equalsConstantTime(hashData.rawHash);
        }

        public int hashCode() {
            int result = Objects.hash(this.cost, this.version);
            result = 31 * result + Arrays.hashCode(this.rawSalt);
            result = 31 * result + Arrays.hashCode(this.rawHash);
            return result;
        }

        public String toString() {
            return "HashData{cost=" + this.cost + ", version=" + this.version + ", rawSalt=" + Bytes.wrap(this.rawSalt).encodeHex() + ", rawHash=" + Bytes.wrap(this.rawHash).encodeHex() + '}';
        }
    }

    public static final class Hasher {
        private final Charset defaultCharset = BCrypt.access$200();
        private final Version version;
        private final SecureRandom secureRandom;
        private final LongPasswordStrategy longPasswordStrategy;

        private Hasher(Version version, SecureRandom secureRandom, LongPasswordStrategy longPasswordStrategy) {
            this.version = version;
            this.secureRandom = secureRandom;
            this.longPasswordStrategy = longPasswordStrategy;
        }

        public char[] hashToChar(int cost, char[] password) {
            return this.defaultCharset.decode(ByteBuffer.wrap(this.hash(cost, password))).array();
        }

        public String hashToString(int cost, char[] password) {
            return new String(this.hash(cost, password), this.defaultCharset);
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        public byte[] hash(int cost, char[] password) {
            byte[] byArray;
            if (password == null) {
                throw new IllegalArgumentException("provided password must not be null");
            }
            byte[] passwordBytes = null;
            try {
                passwordBytes = Bytes.from(password, this.defaultCharset).array();
                byArray = this.hash(cost, Bytes.random(16, this.secureRandom).array(), passwordBytes);
                Bytes.wrapNullSafe(passwordBytes).mutable().secureWipe();
            }
            catch (Throwable throwable) {
                Bytes.wrapNullSafe(passwordBytes).mutable().secureWipe();
                throw throwable;
            }
            return byArray;
        }

        public byte[] hash(int cost, byte[] password) {
            return this.hash(cost, Bytes.random(16, this.secureRandom).array(), password);
        }

        public byte[] hash(int cost, byte[] salt, byte[] password) {
            return this.version.formatter.createHashMessage(this.hashRaw(cost, salt, password));
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        public HashData hashRaw(int cost, byte[] salt, byte[] password) {
            if (cost > 31 || cost < 4) {
                throw new IllegalArgumentException("cost factor must be between 4 and 31, was " + cost);
            }
            if (salt == null) {
                throw new IllegalArgumentException("salt must not be null");
            }
            if (salt.length != 16) {
                throw new IllegalArgumentException("salt must be exactly 16 bytes, was " + salt.length);
            }
            if (password == null) {
                throw new IllegalArgumentException("provided password must not be null");
            }
            if (!this.version.appendNullTerminator && password.length == 0) {
                throw new IllegalArgumentException("provided password must at least be length 1 if no null terminator is appended");
            }
            if (password.length > this.version.allowedMaxPwLength) {
                password = this.longPasswordStrategy.derive(password);
            }
            byte[] pwWithNullTerminator = this.version.appendNullTerminator ? Bytes.wrap(password).append((byte)0).array() : Bytes.wrap(password).copy().array();
            try {
                byte[] hash = new BCryptOpenBSDProtocol().cryptRaw(1L << (int)((long)cost), salt, pwWithNullTerminator);
                HashData hashData = new HashData(cost, this.version, salt, this.version.useOnly23bytesForHash ? Bytes.wrap(hash).resize(23, BytesTransformer.ResizeTransformer.Mode.RESIZE_KEEP_FROM_ZERO_INDEX).array() : hash);
                return hashData;
            }
            finally {
                Bytes.wrapNullSafe(pwWithNullTerminator).mutable().secureWipe();
            }
        }
    }
}
