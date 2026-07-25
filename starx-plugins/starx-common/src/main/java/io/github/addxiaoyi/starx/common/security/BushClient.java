/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.security;

import io.github.addxiaoyi.starx.common.security.HttpClient;
import java.util.logging.Logger;

public final class BushClient {
    private static final Logger logger = Logger.getLogger(BushClient.class.getName());
    private static final String BLOSSOM_URL = "https://blossom.pvphub.co/prd/";
    private final String baseUrl;

    public BushClient() {
        this(BLOSSOM_URL);
    }

    public BushClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isIpBlacklisted(String ip) {
        return this.check(ip).status() == Status.BLOCKED;
    }

    public Check check(String ip) {
        HttpClient.Response<IpInfo> response = HttpClient.post(this.baseUrl + "ip")
                .bodyJson(new IpRequest(ip))
                .send(IpInfo.class);
        if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null) {
            return new Check(Status.BLOCKED, response.body().reason);
        }
        if (response.statusCode() == 404) {
            return new Check(Status.CLEAR, null);
        }
        return new Check(Status.UNAVAILABLE, null);
    }

    public enum Status { BLOCKED, CLEAR, UNAVAILABLE }

    public record Check(Status status, String reason) {}

    static final class IpRequest {
        final String ip;

        IpRequest(String ip) {
            this.ip = ip;
        }
    }

    static final class IpInfo {
        String ip;
        String reason;

        IpInfo() {
        }
    }
}
