/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class RecoveryCodeGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_COUNT = 8;
    private static final int CODE_LENGTH = 10;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private RecoveryCodeGenerator() {
    }

    public static List<String> generate() {
        ArrayList<String> codes = new ArrayList<String>();
        for (int i = 0; i < 8; ++i) {
            StringBuilder sb = new StringBuilder(10);
            for (int j = 0; j < 10; ++j) {
                sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }
}
