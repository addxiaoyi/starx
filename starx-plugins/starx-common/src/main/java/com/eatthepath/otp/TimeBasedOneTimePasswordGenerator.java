/*
 * Decompiled with CFR 0.152.
 */
package com.eatthepath.otp;

import com.eatthepath.otp.HmacOneTimePasswordGenerator;
import com.eatthepath.otp.UncheckedNoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

public class TimeBasedOneTimePasswordGenerator {
    private final HmacOneTimePasswordGenerator hotp;
    private final Duration timeStep;
    public static final Duration DEFAULT_TIME_STEP = Duration.ofSeconds(30L);
    public static final String TOTP_ALGORITHM_HMAC_SHA1 = "HmacSHA1";
    public static final String TOTP_ALGORITHM_HMAC_SHA256 = "HmacSHA256";
    public static final String TOTP_ALGORITHM_HMAC_SHA512 = "HmacSHA512";

    public TimeBasedOneTimePasswordGenerator() {
        this(DEFAULT_TIME_STEP);
    }

    public TimeBasedOneTimePasswordGenerator(Duration timeStep) {
        this(timeStep, 6);
    }

    public TimeBasedOneTimePasswordGenerator(Duration timeStep, int passwordLength) {
        this(timeStep, passwordLength, TOTP_ALGORITHM_HMAC_SHA1);
    }

    public TimeBasedOneTimePasswordGenerator(Duration timeStep, int passwordLength, String algorithm) throws UncheckedNoSuchAlgorithmException {
        this.hotp = new HmacOneTimePasswordGenerator(passwordLength, algorithm);
        this.timeStep = timeStep;
    }

    public int generateOneTimePassword(Key key, Instant timestamp) throws InvalidKeyException {
        return this.hotp.generateOneTimePassword(key, timestamp.toEpochMilli() / this.timeStep.toMillis());
    }

    public String generateOneTimePasswordString(Key key, Instant timestamp) throws InvalidKeyException {
        return this.generateOneTimePasswordString(key, timestamp, Locale.getDefault());
    }

    public String generateOneTimePasswordString(Key key, Instant timestamp, Locale locale) throws InvalidKeyException {
        return this.hotp.formatOneTimePassword(this.generateOneTimePassword(key, timestamp), locale);
    }

    public Duration getTimeStep() {
        return this.timeStep;
    }

    public int getPasswordLength() {
        return this.hotp.getPasswordLength();
    }

    public String getAlgorithm() {
        return this.hotp.getAlgorithm();
    }
}
