/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.crypto;

import at.favre.lib.crypto.bcrypt.BCrypt;

public final class PasswordHasher {
    private static final int COST_FACTOR = 12;

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray());
    }

    public static boolean verify(String password, String hash) {
        return BCrypt.verifyer().verify((char[])password.toCharArray(), (char[])hash.toCharArray()).verified;
    }
}
