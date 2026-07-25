/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.security;

public final class PasswordValidator {
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    private PasswordValidator() {
    }

    public static String validate(String password) {
        if (password == null || password.isEmpty()) {
            return "\u5bc6\u7801\u4e0d\u80fd\u4e3a\u7a7a";
        }
        if (password.length() < 8) {
            return "\u5bc6\u7801\u957f\u5ea6\u81f3\u5c118\u4f4d";
        }
        if (password.length() > 128) {
            return "\u5bc6\u7801\u957f\u5ea6\u4e0d\u80fd\u8d85\u8fc7128\u4f4d";
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); ++i) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) break;
        }
        if (!hasLetter || !hasDigit) {
            return "\u5bc6\u7801\u5fc5\u987b\u5305\u542b\u5b57\u6bcd\u548c\u6570\u5b57";
        }
        return null;
    }
}
