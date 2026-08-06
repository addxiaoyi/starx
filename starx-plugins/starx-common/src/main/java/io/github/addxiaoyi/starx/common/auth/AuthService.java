/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.common.auth;

import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthSession;
import io.github.addxiaoyi.starx.common.auth.SessionManager;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthBridge;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.crypto.PasswordHasher;
import io.github.addxiaoyi.starx.common.crypto.RecoveryCodeGenerator;
import io.github.addxiaoyi.starx.common.crypto.TotpGenerator;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcTrustedDeviceRepository;
import io.github.addxiaoyi.starx.common.model.IpSession;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.security.BruteForceProtector;
import io.github.addxiaoyi.starx.common.security.PasswordValidator;
import io.github.addxiaoyi.starx.common.platform.RiskDecisionEngine;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuthService {
    private static final Logger logger = Logger.getLogger(AuthService.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type RECOVERY_CODE_HASHES_TYPE =
        new TypeToken<List<String>>() {}.getType();
    private static final int RECOVERY_CODE_UPDATE_ATTEMPTS = 3;
    private static final Duration TOTP_ENROLLMENT_TTL = Duration.ofMinutes(5);
    private final JdbcUserRepository userRepository;
    private final EventBus eventBus;
    private final SessionManager sessionManager;
    private final BruteForceProtector bruteForceProtector;
    private final UniAuthConfig uniauthConfig;
    private final UniAuthBridge uniauthBridge;
    private final IpSessionStore ipSessionStore;
    private final JdbcTrustedDeviceRepository trustedDevices;
    private final Map<UUID, PendingTotp> pendingTotp = new ConcurrentHashMap<>();
    private final RiskDecisionEngine riskDecisions = new RiskDecisionEngine();
    private volatile WebLoginApprovalGateway webLoginApprovals;

    // 免密配置
    private int ipBypassMinutes = 30;  // 同 IP 免密有效期（分钟）
    private boolean premiumBypass = true;  // 正版玩家免密
    private boolean floodgateBypass = true;  // 基岩版免密
    private boolean skinSiteBypass = true;  // 皮肤站免密

    public AuthService(JdbcUserRepository userRepository, EventBus eventBus, SessionManager sessionManager, UniAuthConfig uniauthConfig, UniAuthBridge uniauthBridge, IpSessionStore ipSessionStore) {
        this(userRepository, eventBus, sessionManager, uniauthConfig, uniauthBridge,
            ipSessionStore, null);
    }

    public AuthService(
        JdbcUserRepository userRepository,
        EventBus eventBus,
        SessionManager sessionManager,
        UniAuthConfig uniauthConfig,
        UniAuthBridge uniauthBridge,
        IpSessionStore ipSessionStore,
        JdbcTrustedDeviceRepository trustedDevices
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.bruteForceProtector = new BruteForceProtector();
        this.uniauthConfig = Objects.requireNonNullElseGet(
            uniauthConfig, UniAuthConfig::defaults);
        this.uniauthBridge = uniauthBridge;
        this.ipSessionStore = Objects.requireNonNullElseGet(
            ipSessionStore, InMemoryIpSessionStore::new);
        this.trustedDevices = trustedDevices;
    }

    public AuthService(JdbcUserRepository userRepository, EventBus eventBus, SessionManager sessionManager) {
        this.userRepository = userRepository;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.bruteForceProtector = new BruteForceProtector();
        this.uniauthConfig = UniAuthConfig.defaults();
        this.uniauthBridge = null;
        this.ipSessionStore = new InMemoryIpSessionStore();
        this.trustedDevices = null;
    }

    public AuthService(
        JdbcUserRepository userRepository,
        EventBus eventBus,
        SessionManager sessionManager,
        JdbcTrustedDeviceRepository trustedDevices
    ) {
        this.userRepository = userRepository;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.bruteForceProtector = new BruteForceProtector();
        this.uniauthConfig = UniAuthConfig.defaults();
        this.uniauthBridge = null;
        this.ipSessionStore = new InMemoryIpSessionStore();
        this.trustedDevices = Objects.requireNonNull(trustedDevices, "trustedDevices");
    }

    public AuthService(
        JdbcUserRepository userRepository,
        EventBus eventBus,
        SessionManager sessionManager,
        IpSessionStore ipSessionStore,
        JdbcTrustedDeviceRepository trustedDevices
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.bruteForceProtector = new BruteForceProtector();
        this.uniauthConfig = UniAuthConfig.defaults();
        this.uniauthBridge = null;
        this.ipSessionStore = Objects.requireNonNull(ipSessionStore, "ipSessionStore");
        this.trustedDevices = Objects.requireNonNull(trustedDevices, "trustedDevices");
    }

    /**
     * 检查是否应跳过认证（免密登录）
     *
     * @param uuid 玩家 UUID
     * @param ipAddress IP 地址
     * @param isPremium 是否为正版玩家
     * @param isFloodgate 是否为基岩版玩家
     * @param isSkinSite 是否为皮肤站登录
     * @return 是否应跳过认证
     */
    public boolean shouldBypassAuth(UUID uuid, String ipAddress, boolean isPremium, boolean isFloodgate, boolean isSkinSite) {
        return this.shouldBypassAuth(
            uuid, ipAddress, null, isPremium, isFloodgate, isSkinSite);
    }

    public boolean shouldBypassAuth(
        UUID uuid, String ipAddress, String deviceId,
        boolean isPremium, boolean isFloodgate, boolean isSkinSite) {
        // 如果用户未注册，不跳过
        if (!isUserRegistered(uuid)) {
            return false;
        }

        // 正版玩家免密
        if (isPremium && premiumBypass) {
            return true;
        }

        // 基岩版免密
        if (isFloodgate && floodgateBypass) {
            return true;
        }

        // 皮肤站免密
        if (isSkinSite && skinSiteBypass) {
            return true;
        }

        // Same IP password reuse is intentionally short-lived and configurable.
        if (ipBypassMinutes > 0 && ipAddress != null && ipSessionStore != null) {
            if (ipSessionStore.hasRecentSessionMinutes(
                uuid, ipAddress, deviceId, ipBypassMinutes)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 记录成功登录（用于免密检查）
     */
    public void recordSuccessfulLogin(UUID uuid, String ipAddress, String source) {
        this.recordSuccessfulLogin(uuid, ipAddress, source, null);
    }

    public void recordSuccessfulLogin(
        UUID uuid, String ipAddress, String source, String deviceId) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        if (ipSessionStore == null) {
            return;
        }
        IpSession session = IpSession.create(uuid, ipAddress, source, deviceId);
        ipSessionStore.save(session);
    }

    /**
     * 获取玩家的最新登录会话
     */
    public Optional<IpSession> getLatestLoginSession(UUID uuid) {
        if (ipSessionStore == null) {
            return Optional.empty();
        }
        return ipSessionStore.findLatestByUuid(uuid);
    }

    /**
     * 设置 IP 免密有效期
     */
    public void setIpBypassHours(int hours) {
        this.ipBypassMinutes = Math.max(0, hours) * 60;
    }

    public void setIpBypassMinutes(int minutes) {
        this.ipBypassMinutes = Math.max(0, minutes);
    }

    /**
     * 设置是否启用正版免密
     */
    public void setPremiumBypass(boolean enabled) {
        this.premiumBypass = enabled;
    }

    /**
     * 设置是否启用基岩版免密
     */
    public void setFloodgateBypass(boolean enabled) {
        this.floodgateBypass = enabled;
    }

    /**
     * 设置是否启用皮肤站免密
     */
    public void setSkinSiteBypass(boolean enabled) {
        this.skinSiteBypass = enabled;
    }

    /**
     * 获取当前免密配置
     */
    public BypassConfig getBypassConfig() {
        return new BypassConfig(ipBypassMinutes, premiumBypass, floodgateBypass, skinSiteBypass);
    }

    /**
     * 免密配置记录
     */
    public record BypassConfig(int ipBypassMinutes, boolean premiumBypass, boolean floodgateBypass, boolean skinSiteBypass) {}

    public boolean openConnection(AuthLease lease, UUID uuid, String username, InetAddress address) {
        return this.openConnection(lease, uuid, username, address, null);
    }

    public boolean openConnection(
        AuthLease lease, UUID uuid, String username, InetAddress address, String deviceId) {
        return this.sessionManager.open(uuid, username, address, deviceId, lease) != null;
    }

    public boolean closeConnection(UUID uuid, AuthLease lease) {
        return this.sessionManager.remove(uuid, lease);
    }

    public synchronized void bindWebLoginApprovalGateway(WebLoginApprovalGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        if (this.webLoginApprovals != null) {
            throw new IllegalStateException("Web login approval gateway is already bound");
        }
        this.webLoginApprovals = gateway;
    }

    public AuthResult requestWebLoginApproval(AuthLease lease, UUID uuid, String username) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(uuid, "uuid");
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("请先注册游戏账号，再使用网站登录");
        }
        if (!this.sessionManager.isState(uuid, lease, AuthSession.State.GUEST)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }

        try {
            String approvalUrl = Objects.requireNonNull(this.webLoginApprovals, "webLoginApprovals")
                .request(uuid, optional.get().username(), lease);
            if (approvalUrl.isBlank()) {
                throw new IllegalStateException("Web login approval URL is blank");
            }
            return AuthResult.webApproval(approvalUrl);
        } catch (RuntimeException error) {
            this.sessionManager.transition(
                uuid, lease, AuthSession.State.WEB_APPROVAL_PENDING, AuthSession.State.GUEST);
            logger.log(Level.WARNING, "Unable to create web login approval", error);
            return AuthResult.failure("网页登录确认当前不可用，请稍后重试");
        }
    }

    public AuthResult autoLogin(AuthLease lease, UUID uuid, String username, InetAddress address) {
        return this.autoLoginTrusted(lease, uuid, username, address, "premium", true);
    }

    public AuthResult autoLoginTrusted(
            AuthLease lease,
            UUID uuid,
            String username,
            InetAddress address,
            String source,
            boolean premium) {
        if (!this.sessionManager.isState(uuid, lease, AuthSession.State.GUEST)) {
            return AuthResult.failure("\u670d\u52a1\u5668\u7e41\u5fd9\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5");
        }
        if (source == null || source.isBlank()) {
            return AuthResult.failure("可信身份来源无效");
        }
        Instant now = Instant.now();
        if (!this.userRepository.existsByUuid(uuid)) {
            this.userRepository.create(new StarxUser(
                    uuid,
                    username,
                    null,
                    null,
                    null,
                    premium,
                    now,
                    now,
                    null,
                    List.of(),
                    "",
                    source,
                    "completed",
                    null,
                    address == null ? null : address.getHostAddress(),
                    "",
                    "",
                    0L,
                    null,
                    false));
        } else {
            this.userRepository.updateLastLogin(uuid, now);
            if (premium) {
                this.userRepository.updatePremium(uuid, true);
            }
        }
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        this.eventBus.publish("player:login:success", Map.of(
                "uuid", uuid,
                "username", username,
                "source", source));
        return AuthResult.success("可信身份自动登录成功", AuthSession.State.AUTHENTICATED);
    }

    public AuthResult register(
        AuthLease lease,
        UUID uuid,
        String username,
        String password,
        String email
    ) {
        if (!this.sessionManager.isState(uuid, lease, AuthSession.State.GUEST)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        String passwordError = PasswordValidator.validate(password);
        if (passwordError != null) {
            return AuthResult.failure(passwordError);
        }
        if (this.userRepository.existsByUsernameOrUuid(username, uuid)) {
            return AuthResult.failure("\u7528\u6237\u540d\u5df2\u88ab\u5360\u7528\u6216\u5df2\u6ce8\u518c");
        }
        String normalizedEmail = AuthService.normalizeEmail(email);
        StarxUser user = new StarxUser(uuid, username, normalizedEmail, PasswordHasher.hash(password), null, false, Instant.now(), null, null, null, null, "local", "completed", null, null, null, null, 0L, null, false);
        this.userRepository.create(user);
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        this.rememberTrustedDevice(uuid, lease);
        this.sessionManager.get(uuid, lease)
            .map(AuthSession::address)
            .filter(Objects::nonNull)
            .map(InetAddress::getHostAddress)
            .ifPresent(address -> this.recordSuccessfulLogin(
                uuid, address, "local", this.sessionDeviceId(uuid, lease)));
        this.eventBus.publish("player:register", Map.of("uuid", uuid, "username", username, "email", normalizedEmail == null ? "" : normalizedEmail));
        return AuthResult.success("\u6ce8\u518c\u6210\u529f");
    }

    public AuthResult login(
        AuthLease lease,
        UUID uuid,
        String username,
        String password,
        String totpCode,
        InetAddress address,
        String deviceId
    ) {
        BruteForceProtector.Check bruteForce = this.bruteForceProtector.check(uuid);
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.LOCKED) {
            long sec = bruteForce.waitMs() / 1000L;
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef\u6b21\u6570\u8fc7\u591a\uff0c\u8bf7" + sec + "\u79d2\u540e\u518d\u8bd5");
        }
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.DELAYED) {
            long sec = bruteForce.waitMs() / 1000L;
            return AuthResult.failure("\u8bf7\u7b49\u5f85" + sec + "\u79d2\u540e\u518d\u8bd5");
        }
        if (this.uniauthConfig.enabled() && this.uniauthConfig.bridgeMode() && this.uniauthBridge != null) {
            logger.log(Level.FINE, "Using UniAuth bridge for user: {0}", username);
            CompletableFuture<UniAuthBridge.BridgeResult> bridgeResultFuture = this.uniauthBridge.authenticate(uuid, username, password);
            try {
                UniAuthBridge.BridgeResult bridgeResult = bridgeResultFuture.get();
                if (bridgeResult.success() && bridgeResult.user() != null) {
                    this.bruteForceProtector.clear(uuid);
                    return this.authenticate(
                        bridgeResult.user(), lease, AuthSession.State.GUEST);
                }
                this.bruteForceProtector.recordFailure(uuid);
                this.publishLoginFailed(uuid, username, bridgeResult.message());
                return AuthResult.failure(bridgeResult.message());
            }
            catch (Exception e) {
                logger.log(Level.WARNING, "UniAuth bridge failed, falling back to local auth", e);
            }
        }
        return this.loginLocal(lease, uuid, username, password, totpCode, address, deviceId);
    }

    private AuthResult loginLocal(
        AuthLease lease,
        UUID uuid,
        String username,
        String password,
        String totpCode,
        InetAddress address,
        String deviceId
    ) {
        if (!this.sessionManager.isState(uuid, lease, AuthSession.State.GUEST)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            this.bruteForceProtector.recordFailure(uuid);
            int failures = this.bruteForceProtector.getAttemptCount(uuid);
            if (failures >= 3) {
                this.eventBus.publish("player:brute-force", Map.of("uuid", uuid, "username", username, "attempts", failures, "ip", address == null ? "unknown" : address.getHostAddress()));
            }
            this.publishLoginFailed(uuid, username, "\u5bc6\u7801\u9519\u8bef");
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        RiskDecisionEngine.Decision loginDecision =
            this.loginDecision(user, deviceId, address);
        if (loginDecision.action() != RiskDecisionEngine.Action.ALLOW) {
            if (loginDecision.action() == RiskDecisionEngine.Action.REQUIRE_TOTP
                && totpCode != null
                && TotpGenerator.verify(user.totpSecret(), totpCode, Instant.now())) {
                return this.authenticate(user, lease, AuthSession.State.GUEST);
            }
            AuthSession.State pendingState =
                loginDecision.action() == RiskDecisionEngine.Action.REQUIRE_WEB_APPROVAL
                    ? AuthSession.State.WEB_APPROVAL_PENDING
                    : AuthSession.State.AUTHENTICATING;
            if (!this.sessionManager.transition(
                    uuid, lease, AuthSession.State.GUEST, pendingState)) {
                return AuthResult.failure("认证会话已过期，请重新连接。");
            }
            if (pendingState == AuthSession.State.WEB_APPROVAL_PENDING) {
                try {
                    String url = Objects.requireNonNull(this.webLoginApprovals, "webLoginApprovals")
                        .request(uuid, user.username(), lease);
                    return AuthResult.webApproval(url);
                } catch (RuntimeException error) {
                    if (user.totpSecret() != null && !user.totpSecret().isBlank()) {
                        this.sessionManager.transition(
                            uuid, lease, AuthSession.State.WEB_APPROVAL_PENDING,
                            AuthSession.State.AUTHENTICATING);
                        return AuthResult.success(
                            "网页确认暂不可用，请输入二步验证码",
                            AuthSession.State.AUTHENTICATING);
                    }
                    this.sessionManager.transition(
                        uuid, lease, AuthSession.State.WEB_APPROVAL_PENDING,
                        AuthSession.State.GUEST);
                    logger.log(Level.WARNING, "Unable to create web login approval", error);
                    return AuthResult.failure("网页确认暂不可用，请稍后重试");
                }
            }
            return AuthResult.success("\u8bf7\u8f93\u5165\u4e8c\u6b65\u9a8c\u8bc1\u7801", AuthSession.State.AUTHENTICATING);
        }
        this.bruteForceProtector.clear(uuid);
        return this.authenticate(user, lease, AuthSession.State.GUEST);
    }

    public AuthResult verifyTotp(AuthLease lease, UUID uuid, String code) {
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (user.totpSecret() == null) {
            return AuthResult.failure("\u672a\u5f00\u542f\u4e8c\u6b65\u9a8c\u8bc1");
        }
        if (!this.sessionManager.isState(
                uuid, lease, AuthSession.State.AUTHENTICATING)) {
            return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
        }
        if (!TotpGenerator.verify(user.totpSecret(), code, Instant.now())) {
            this.publishLoginFailed(uuid, user.username(), "\u4e8c\u6b65\u9a8c\u8bc1\u7801\u9519\u8bef");
            return AuthResult.failure("\u4e8c\u6b65\u9a8c\u8bc1\u7801\u9519\u8bef");
        }
        return this.authenticate(user, lease, AuthSession.State.AUTHENTICATING);
    }

    public boolean isAuthenticated(AuthLease lease, UUID uuid) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(uuid, "uuid");
        return this.sessionManager.isState(uuid, lease, AuthSession.State.AUTHENTICATED);
    }

    public AuthResult approveWebLogin(AuthLease lease, UUID uuid) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(uuid, "uuid");
        if (this.sessionManager.isState(uuid, lease, AuthSession.State.AUTHENTICATED)) {
            return AuthResult.success("网页登录确认已完成");
        }
        if (!this.sessionManager.isState(
                uuid, lease, AuthSession.State.WEB_APPROVAL_PENDING)) {
            return AuthResult.failure("网页登录确认已过期，请重新连接。");
        }
        StarxUser user = this.userRepository.findFullByUuid(uuid).orElse(null);
        if (user == null) {
            return AuthResult.failure("用户未注册");
        }
        return this.authenticate(user, lease, AuthSession.State.WEB_APPROVAL_PENDING);
    }

    public AuthResult logout(UUID uuid) {
        Optional<AuthSession> session = this.sessionManager.get(uuid);
        String username = session.map(AuthSession::username).orElse("");
        this.sessionManager.remove(uuid);
        this.eventBus.publish("player:logout", Map.of("uuid", uuid, "username", username));
        return AuthResult.success("\u5df2\u767b\u51fa");
    }

    public AuthResult changePassword(UUID uuid, String oldPassword, String newPassword) {
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        if (!PasswordHasher.verify(oldPassword, optional.get().passwordHash())) {
            return AuthResult.failure("\u539f\u5bc6\u7801\u9519\u8bef");
        }
        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) return AuthResult.failure(passwordError);
        this.revokeCredentialTrust(uuid);
        this.userRepository.updatePassword(uuid, PasswordHasher.hash(newPassword));
        return AuthResult.success("\u5bc6\u7801\u4fee\u6539\u6210\u529f");
    }

    public AuthResult addTrustedDevice(UUID uuid, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return AuthResult.failure("\u8bbe\u5907\u6807\u8bc6\u4e0d\u80fd\u4e3a\u7a7a");
        }
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        ArrayList<String> devices = new ArrayList<String>(optional.get().trustedDevices());
        if (devices.contains(deviceId)) {
            return AuthResult.success("\u8bbe\u5907\u5df2\u4fe1\u4efb");
        }
        devices.add(deviceId);
        this.userRepository.updateTrustedDevices(uuid, devices);
        return AuthResult.success("\u8bbe\u5907\u5df2\u6dfb\u52a0\u4fe1\u4efb");
    }

    public AuthResult removeTrustedDevice(UUID uuid, String deviceId) {
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        ArrayList<String> devices = new ArrayList<String>(optional.get().trustedDevices());
        if (!devices.remove(deviceId)) {
            return AuthResult.failure("\u8bbe\u5907\u4e0d\u5728\u4fe1\u4efb\u5217\u8868\u4e2d");
        }
        this.userRepository.updateTrustedDevices(uuid, devices);
        return AuthResult.success("\u8bbe\u5907\u5df2\u53d6\u6d88\u4fe1\u4efb");
    }

    public boolean isTrustedDevice(UUID uuid, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return this.userRepository.findTrustedDevicesByUuid(uuid).map(json -> {
            List<String> devices = AuthService.parseTrustedDevices(json);
            return devices.contains(deviceId);
        }).orElse(false);
    }

    public boolean isUserRegistered(UUID uuid) {
        return this.userRepository.existsByUuid(uuid);
    }

    public Optional<AuthSession.State> getSessionState(UUID uuid) {
        return this.sessionManager.get(uuid).map(AuthSession::state);
    }

    public boolean cancelAuthentication(UUID uuid, AuthLease lease) {
        return this.sessionManager.removeIfState(
            uuid, lease, AuthSession.State.AUTHENTICATING);
    }

    public boolean isTotpEnabled(UUID uuid) {
        return this.userRepository.findTotpSecretByUuid(uuid).isPresent();
    }

    public AuthResult verifyRecoveryCode(AuthLease lease, UUID uuid, String recoveryCode) {
        if (recoveryCode == null || recoveryCode.isBlank()) {
            return AuthResult.failure("\u6062\u590d\u7801\u65e0\u6548");
        }
        String candidate = recoveryCode.trim();
        if (!this.sessionManager.isState(
                uuid, lease, AuthSession.State.AUTHENTICATING)) {
            return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
        }

        for (int attempt = 0; attempt < RECOVERY_CODE_UPDATE_ATTEMPTS; attempt++) {
            if (!this.sessionManager.isState(
                    uuid, lease, AuthSession.State.AUTHENTICATING)) {
                return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
            }
            Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
            if (optional.isEmpty()) {
                return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
            }
            StarxUser user = optional.get();
            String stored = user.recoveryCodes();
            List<String> hashes = parseRecoveryCodeHashes(stored);
            if (hashes.isEmpty()) {
                return AuthResult.failure("\u65e0\u53ef\u7528\u6062\u590d\u7801");
            }

            int matchedIndex = -1;
            for (int index = 0; index < hashes.size(); index++) {
                if (PasswordHasher.verify(candidate, hashes.get(index))) {
                    matchedIndex = index;
                    break;
                }
            }
            if (matchedIndex < 0) {
                return AuthResult.failure("\u6062\u590d\u7801\u65e0\u6548");
            }

            List<String> remaining = new ArrayList<>(hashes);
            remaining.remove(matchedIndex);
            if (this.userRepository.replaceRecoveryCodes(
                    uuid, stored, encodeRecoveryCodeHashes(remaining))) {
                return this.authenticate(user, lease, AuthSession.State.AUTHENTICATING);
            }
        }
        return AuthResult.failure("\u6062\u590d\u7801\u5df2\u66f4\u65b0\uff0c\u8bf7\u91cd\u8bd5");
    }

    public BruteForceProtector bruteForceProtector() {
        return this.bruteForceProtector;
    }

    AuthResult enableTotp(UUID uuid, String password) {
        if (!this.userRepository.existsByUuid(uuid)) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        Optional<String> existingHash = this.userRepository.findPasswordHashByUuid(uuid);
        if (existingHash.isEmpty() || !PasswordHasher.verify(password, existingHash.get())) {
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        String secret = TotpGenerator.generateSecret();
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashedCodes = recoveryCodes.stream()
            .map(PasswordHasher::hash)
            .toList();
        this.revokeSecurityTrust(uuid, true, "totp-enabled");
        if (!this.userRepository.enableTotp(
                uuid, secret, encodeRecoveryCodeHashes(hashedCodes))) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        this.eventBus.publish("player:totp:enabled", Map.of("uuid", uuid, "method", "direct"));
        return AuthResult.totpEnabled(secret, recoveryCodes);
    }

    public TotpEnrollment beginTotpEnrollment(UUID uuid, String password) {
        StarxUser user = this.userRepository.findFullByUuid(uuid)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        if (user.totpSecret() != null && !user.totpSecret().isBlank()) {
            throw new IllegalStateException("二步验证已开启");
        }
        String secret = TotpGenerator.generateSecret();
        Instant expiresAt = Instant.now().plus(TOTP_ENROLLMENT_TTL);
        this.pendingTotp.put(uuid, new PendingTotp(secret, expiresAt));
        return new TotpEnrollment(
            secret,
            TotpProvisioning.uri("StarMC", user.username(), secret).toString(),
            expiresAt);
    }

    public AuthResult confirmTotpEnrollment(UUID uuid, String code) {
        PendingTotp pending = this.pendingTotp.get(uuid);
        if (pending == null || !pending.expiresAt().isAfter(Instant.now())) {
            this.pendingTotp.remove(uuid);
            return AuthResult.failure("二步验证设置已过期，请重新开始");
        }
        if (code == null || !code.matches("\\d{6}")
            || !TotpGenerator.verify(pending.secret(), code, Instant.now())) {
            return AuthResult.failure("验证码错误");
        }
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashedCodes = recoveryCodes.stream().map(PasswordHasher::hash).toList();
        this.revokeSecurityTrust(uuid, true, "totp-enabled");
        if (!this.userRepository.enableTotp(
                uuid, pending.secret(), encodeRecoveryCodeHashes(hashedCodes))) {
            return AuthResult.failure("用户不存在");
        }
        this.pendingTotp.remove(uuid, pending);
        this.eventBus.publish("player:totp:enabled", Map.of(
            "uuid", uuid, "method", "confirmed_enrollment"));
        return AuthResult.totpEnabled(pending.secret(), recoveryCodes);
    }

    public AuthResult rotateRecoveryCodes(UUID uuid, String code) {
        StarxUser user = this.userRepository.findFullByUuid(uuid).orElse(null);
        if (user == null || user.totpSecret() == null || user.totpSecret().isBlank()) {
            return AuthResult.failure("二步验证未开启");
        }
        if (code == null || !TotpGenerator.verify(user.totpSecret(), code.trim(), Instant.now())) {
            return AuthResult.failure("验证码错误");
        }
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashes = recoveryCodes.stream().map(PasswordHasher::hash).toList();
        if (!this.userRepository.replaceRecoveryCodes(
                uuid, user.recoveryCodes(), encodeRecoveryCodeHashes(hashes))) {
            return AuthResult.failure("恢复码已更新，请重试");
        }
        return AuthResult.recoveryCodesRotated(recoveryCodes);
    }

    public AuthResult disableTotp(UUID uuid, String password) {
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        if (user.totpSecret() == null) {
            return AuthResult.failure("\u4e8c\u6b65\u9a8c\u8bc1\u672a\u5f00\u542f");
        }
        this.revokeSecurityTrust(uuid, true, "totp-disabled");
        if (!this.userRepository.disableTotp(uuid)) {
            return AuthResult.failure("二步验证状态已更新，请重试");
        }
        this.pendingTotp.remove(uuid);
        this.eventBus.publish("player:totp:disabled", Map.of("uuid", uuid, "username", user.username()));
        return AuthResult.success("\u4e8c\u6b65\u9a8c\u8bc1\u5df2\u5173\u95ed");
    }

    public AuthResult resetPassword(String username, String newPassword) {
        Optional<StarxUser> optional = this.userRepository.findFullByUsername(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) return AuthResult.failure(passwordError);
        UUID playerId = optional.get().uuid();
        this.revokeCredentialTrust(playerId);
        this.userRepository.updatePassword(playerId, PasswordHasher.hash(newPassword));
        return AuthResult.success("\u5bc6\u7801\u5df2\u91cd\u7f6e");
    }

    private void revokeCredentialTrust(UUID playerId) {
        this.revokeSecurityTrust(playerId, false, "password-changed");
        this.eventBus.publish("player:credentials:changed", Map.of("uuid", playerId));
    }

    private void revokeSecurityTrust(UUID playerId, boolean keepCurrentSession, String reason) {
        if (this.ipSessionStore != null) {
            this.ipSessionStore.deleteByUuid(playerId);
        }
        if (this.trustedDevices != null) {
            this.trustedDevices.revokeAll(playerId, Instant.now());
        }
        this.userRepository.updateTrustedDevices(playerId, List.of());
        if (!keepCurrentSession) this.sessionManager.remove(playerId);
        this.pendingTotp.remove(playerId);
        this.bruteForceProtector.clear(playerId);
        this.eventBus.publish("player:security:changed", Map.of(
            "uuid", playerId,
            "reason", reason,
            "sessionRetained", keepCurrentSession));
    }

    public AuthResult bindEmail(String username, String email) {
        Optional<StarxUser> optional = this.userRepository.findFullByUsername(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        return bindEmail(optional.get(), email);
    }

    public AuthResult bindEmail(UUID uuid, String email) {
        Optional<StarxUser> optional = this.userRepository.findFullByUuid(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        return bindEmail(optional.get(), email);
    }

    private AuthResult bindEmail(StarxUser user, String email) {
        final String normalized;
        try {
            normalized = EmailAddress.normalize(email);
        } catch (IllegalArgumentException | NullPointerException error) {
            return AuthResult.failure("\u90ae\u7bb1\u683c\u5f0f\u4e0d\u6b63\u786e");
        }
        Optional<io.github.addxiaoyi.starx.api.dto.UserDto> existing =
            this.userRepository.findByEmail(normalized);
        if (existing.isPresent() && !existing.get().uuid().equals(user.uuid())) {
            return AuthResult.failure("\u8be5\u90ae\u7bb1\u5df2\u88ab\u5176\u4ed6\u8d26\u53f7\u7ed1\u5b9a");
        }
        if (!this.userRepository.tryUpdateEmail(user.uuid(), normalized)) {
            return AuthResult.failure("\u8be5\u90ae\u7bb1\u5df2\u88ab\u5176\u4ed6\u8d26\u53f7\u7ed1\u5b9a");
        }
        return AuthResult.success("\u90ae\u7bb1\u5df2\u7ed1\u5b9a");
    }

    public AuthResult deleteUser(String username) {
        Optional<StarxUser> optional = this.userRepository.findFullByUsername(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        this.userRepository.delete(optional.get().uuid());
        this.sessionManager.remove(optional.get().uuid());
        return AuthResult.success("\u7528\u6237\u5df2\u5220\u9664");
    }

    private AuthResult authenticate(
        StarxUser user,
        AuthLease lease,
        AuthSession.State expected
    ) {
        if (!this.sessionManager.isState(user.uuid(), lease, expected)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        this.userRepository.updateLastLogin(user.uuid(), Instant.now());
        if (!this.sessionManager.transition(
                user.uuid(), lease, expected, AuthSession.State.AUTHENTICATED)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        this.bruteForceProtector.clear(user.uuid());
        this.rememberTrustedDevice(user.uuid(), lease);
        this.sessionManager.get(user.uuid(), lease)
            .map(AuthSession::address)
            .filter(Objects::nonNull)
            .map(InetAddress::getHostAddress)
            .ifPresent(address -> this.recordSuccessfulLogin(
                user.uuid(), address, "local", this.sessionDeviceId(user.uuid(), lease)));
        this.eventBus.publish("player:login:success", Map.of("uuid", user.uuid(), "username", user.username()));
        return AuthResult.success("\u767b\u5f55\u6210\u529f", AuthSession.State.AUTHENTICATED);
    }

    private boolean isTrustedDevice(StarxUser user, String deviceId) {
        return this.isTrustedDevice(user, deviceId, null);
    }

    private boolean isTrustedDevice(StarxUser user, String deviceId, InetAddress address) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        if (this.trustedDevices != null) {
            Instant now = Instant.now();
            String region = address == null ? "unknown" : RegionKey.from(address);
            return this.trustedDevices.isTrusted(user.uuid(), deviceId, region, now)
                || this.trustedDevices.hasFamiliarRegion(user.uuid(), region, now);
        }
        return user.trustedDevices() != null && user.trustedDevices().contains(deviceId);
    }

    private RiskDecisionEngine.Decision loginDecision(
        StarxUser user, String deviceId, InetAddress address) {
        Instant now = Instant.now();
        boolean trustedDevice;
        boolean familiarRegion;
        if (this.trustedDevices != null) {
            String region = address == null ? "unknown" : RegionKey.from(address);
            trustedDevice = deviceId != null && !deviceId.isBlank()
                && this.trustedDevices.isTrusted(user.uuid(), deviceId, region, now);
            familiarRegion = this.trustedDevices.hasFamiliarRegion(user.uuid(), region, now);
        } else {
            trustedDevice = deviceId != null && !deviceId.isBlank()
                && user.trustedDevices() != null && user.trustedDevices().contains(deviceId);
            familiarRegion = trustedDevice;
        }
        RiskDecisionEngine.Decision decision = this.riskDecisions.decide(
            new RiskDecisionEngine.Input(
                trustedDevice,
                familiarRegion,
                user.totpSecret() != null && !user.totpSecret().isBlank(),
                AddressRisk.score(address),
                this.bruteForceProtector.getAttemptCount(user.uuid()),
                this.webLoginApprovals != null));
        if (decision.action() != RiskDecisionEngine.Action.ALLOW) {
            this.eventBus.publish("player:login:risk-step-up", Map.of(
                "uuid", user.uuid(),
                "score", decision.score(),
                "action", decision.action().name(),
                "reasons", decision.reasons()));
        }
        return decision;
    }

    private void rememberTrustedDevice(UUID playerId, AuthLease lease) {
        if (this.trustedDevices == null) return;
        String deviceId = this.sessionDeviceId(playerId, lease);
        if (deviceId == null || deviceId.isBlank()) return;
        this.sessionManager.get(playerId, lease)
            .map(AuthSession::address)
            .filter(Objects::nonNull)
            .ifPresent(address -> this.trustedDevices.observe(
                playerId,
                deviceId,
                RegionKey.from(address),
                "Minecraft client",
                Instant.now().plus(Duration.ofDays(30)),
                Instant.now()));
    }

    private String sessionDeviceId(UUID playerId, AuthLease lease) {
        return this.sessionManager.get(playerId, lease)
            .map(AuthSession::deviceId)
            .orElse(null);
    }

    private void publishLoginFailed(UUID uuid, String username, String reason) {
        this.eventBus.publish("player:login:failed", Map.of("uuid", uuid, "username", username, "reason", reason));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim();
    }

    private static List<String> parseTrustedDevices(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List parsed = (List)new Gson().fromJson(json, new TypeToken<List<String>>(){}.getType());
            return parsed == null ? List.of() : List.copyOf(parsed);
        }
        catch (Exception e) {
            return List.of();
        }
    }

    private record PendingTotp(String secret, Instant expiresAt) {
    }

    private static String encodeRecoveryCodeHashes(List<String> hashes) {
        return GSON.toJson(hashes);
    }

    private static List<String> parseRecoveryCodeHashes(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            if (encoded.startsWith("[")) {
                List<String> hashes = GSON.fromJson(encoded, RECOVERY_CODE_HASHES_TYPE);
                return hashes == null ? List.of() : List.copyOf(hashes);
            }
            return List.of(encoded.split(","));
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Stored recovery codes are malformed", error);
            return List.of();
        }
    }
}
