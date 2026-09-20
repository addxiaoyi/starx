/*
 * Decompiled with CFR 0.152.
 */
package com.eatthepath.otp;

import java.security.NoSuchAlgorithmException;

public class UncheckedNoSuchAlgorithmException
extends RuntimeException {
    UncheckedNoSuchAlgorithmException(NoSuchAlgorithmException cause) {
        super(cause);
    }

    @Override
    public NoSuchAlgorithmException getCause() {
        return (NoSuchAlgorithmException)super.getCause();
    }
}
