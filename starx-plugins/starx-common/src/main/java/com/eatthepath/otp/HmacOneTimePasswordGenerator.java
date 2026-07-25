/*
 * Decompiled with CFR 0.152.
 */
package com.eatthepath.otp;

import com.eatthepath.otp.UncheckedNoSuchAlgorithmException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.ShortBufferException;

public class HmacOneTimePasswordGenerator {
    private final Mac prototypeMac;
    private final int passwordLength;
    private final int modDivisor;
    private final String formatString;
    public static final int DEFAULT_PASSWORD_LENGTH = 6;
    static final String HOTP_HMAC_ALGORITHM = "HmacSHA1";

    public HmacOneTimePasswordGenerator() {
        this(6);
    }

    public HmacOneTimePasswordGenerator(int passwordLength) {
        this(passwordLength, HOTP_HMAC_ALGORITHM);
    }

    HmacOneTimePasswordGenerator(int passwordLength, String algorithm) throws UncheckedNoSuchAlgorithmException {
        try {
            this.prototypeMac = Mac.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException e) {
            throw new UncheckedNoSuchAlgorithmException(e);
        }
        switch (passwordLength) {
            case 6: {
                this.modDivisor = 1000000;
                this.formatString = "%06d";
                break;
            }
            case 7: {
                this.modDivisor = 10000000;
                this.formatString = "%07d";
                break;
            }
            case 8: {
                this.modDivisor = 100000000;
                this.formatString = "%08d";
                break;
            }
            default: {
                throw new IllegalArgumentException("Password length must be between 6 and 8 digits.");
            }
        }
        this.passwordLength = passwordLength;
    }

    public int generateOneTimePassword(Key key, long counter) throws InvalidKeyException {
        Mac mac = this.getMac();
        ByteBuffer buffer = ByteBuffer.allocate(mac.getMacLength());
        buffer.putLong(0, counter);
        try {
            byte[] array = buffer.array();
            mac.init(key);
            mac.update(array, 0, 8);
            mac.doFinal(array, 0);
        }
        catch (ShortBufferException e) {
            throw new RuntimeException(e);
        }
        int offset = buffer.get(buffer.capacity() - 1) & 0xF;
        return (buffer.getInt(offset) & Integer.MAX_VALUE) % this.modDivisor;
    }

    private Mac getMac() {
        try {
            return (Mac)this.prototypeMac.clone();
        }
        catch (CloneNotSupportedException e) {
            try {
                return Mac.getInstance(this.prototypeMac.getAlgorithm());
            }
            catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public String generateOneTimePasswordString(Key key, long counter) throws InvalidKeyException {
        return this.generateOneTimePasswordString(key, counter, Locale.getDefault());
    }

    public String generateOneTimePasswordString(Key key, long counter, Locale locale) throws InvalidKeyException {
        return this.formatOneTimePassword(this.generateOneTimePassword(key, counter), locale);
    }

    String formatOneTimePassword(int oneTimePassword, Locale locale) {
        return String.format(locale, this.formatString, oneTimePassword);
    }

    public int getPasswordLength() {
        return this.passwordLength;
    }

    public String getAlgorithm() {
        return this.prototypeMac.getAlgorithm();
    }
}
