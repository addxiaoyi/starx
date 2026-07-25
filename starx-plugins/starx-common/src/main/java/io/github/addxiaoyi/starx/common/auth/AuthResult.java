/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.common.auth.AuthSession;
import java.util.List;

public record AuthResult(
        boolean success,
        String message,
        AuthSession.State state,
        String totpSecret,
        List<String> recoveryCodes,
        String webApprovalUrl) {
    public AuthResult {
        recoveryCodes = recoveryCodes == null ? List.of() : List.copyOf(recoveryCodes);
    }

    public static AuthResult success(String message) {
        return new AuthResult(true, message, AuthSession.State.AUTHENTICATED, null, List.of(), null);
    }

    public static AuthResult success(String message, AuthSession.State state) {
        return new AuthResult(true, message, state, null, List.of(), null);
    }

    public static AuthResult failure(String message) {
        return new AuthResult(false, message, AuthSession.State.GUEST, null, List.of(), null);
    }

    public static AuthResult totpEnabled(String secret, List<String> recoveryCodes) {
        return new AuthResult(
            true, "\u4e8c\u6b65\u9a8c\u8bc1\u5df2\u5f00\u542f", AuthSession.State.AUTHENTICATED,
            secret, recoveryCodes, null);
    }

    public static AuthResult recoveryCodesRotated(List<String> recoveryCodes) {
        return new AuthResult(
            true, "\u6062\u590d\u7801\u5df2\u66f4\u65b0", AuthSession.State.AUTHENTICATED,
            null, recoveryCodes, null);
    }

    public static AuthResult webApproval(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Web approval URL is required");
        }
        return new AuthResult(
            true, "请在网页确认本次登录", AuthSession.State.WEB_APPROVAL_PENDING,
            null, List.of(), url.trim());
    }
}
