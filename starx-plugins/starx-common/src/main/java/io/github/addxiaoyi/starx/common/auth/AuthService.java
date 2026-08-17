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
import io.github.addxiaoyi.starx.common.identity.IdentitySource;
import io.github.addxiaoyi.starx.common.model.IpSession;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.common.security.BruteForceProtector;
import io.github.addxiaoyi.starx.common.security.PasswordValidator;
import io.github.addxiaoyi.starx.common.platform.RiskDecisionEngine;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuthService {
    private static final Logger logger = Logger.getLogger(AuthService.class.getName());
    private static final Gson GSON = new Gson();
    private static final Type RECOVERY_CODE_HASHES_TYPE =
        new TypeToken<List<String>>() {}.getType();
    private static final int RECOVERY_CODE_UPDATE_ATTEMPTS = 3;
    private static final Duration TOTP_ENROLLMENT_TTL = Duration.ofMinutes(5);
    private static final String EXTERNAL_HANDSHAKE_SOURCE = "external-handshake";
    private static final String EXTERNAL_HANDSHAKE_IDENTITY_ERROR =
        "外部握手未提供可验证的玩家身份";
    private final JdbcUserRepository userRepository;
    private final EventBus eventBus;
    private final SessionManager sessionManager;
    private final BruteForceProtector bruteForceProtector;
    private final UniAuthConfig uniauthConfig;
    private final UniAuthBridge uniauthBridge;
    private final IpSessionStore ipSessionStore;
    private final JdbcTrustedDeviceRepository trustedDevices;
    private volatile Function<UUID, Set<UUID>> minecraftIdentityResolver = uuid -> Set.of(uuid);
    private volatile Function<String, Optional<StarxUser>> usernameResolver;
    private volatile java.util.function.Consumer<UUID> accountErasure;
    private volatile MinecraftIdentityObserver minecraftIdentityObserver =
        (uuid, username, source) -> { };
    private volatile java.util.function.Consumer<UUID> minecraftIdentityRollback = ignored -> { };
    private final Map<UUID, ProvisionedLogin> provisionedLogins = new ConcurrentHashMap<>();
    private final Map<UUID, PendingTotp> pendingTotp = new ConcurrentHashMap<>();
    private final RiskDecisionEngine riskDecisions = new RiskDecisionEngine();
    private volatile WebLoginApprovalGateway webLoginApprovals;
    private volatile boolean totpAvailable = true;

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
        this.usernameResolver = userRepository::findFullByUsername;
        this.accountErasure = userRepository::delete;
        this.sessionManager.addExpirationListener(this::cleanupExpiredSession);
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
        this.usernameResolver = userRepository::findFullByUsername;
        this.accountErasure = userRepository::delete;
        this.sessionManager.addExpirationListener(this::cleanupExpiredSession);
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
        this.usernameResolver = userRepository::findFullByUsername;
        this.accountErasure = userRepository::delete;
        this.sessionManager.addExpirationListener(this::cleanupExpiredSession);
    }

    public void bindMinecraftIdentityResolver(Function<UUID, Set<UUID>> resolver) {
        this.minecraftIdentityResolver = Objects.requireNonNull(resolver, "resolver");
        if (this.uniauthBridge != null) {
            this.uniauthBridge.bindMinecraftIdentityResolver(resolver);
        }
    }

  public void bindUsernameResolver(Function<String, Optional<StarxUser>> resolver) {
        this.usernameResolver = Objects.requireNonNull(resolver, "resolver");
        if (this.uniauthBridge != null) {
            this.uniauthBridge.bindUsernameResolver(resolver);
        }
  }

  public void bindAccountErasure(java.util.function.Consumer<UUID> erasure) {
    this.accountErasure = Objects.requireNonNull(erasure, "erasure");
  }

  public void bindMinecraftIdentityObserver(MinecraftIdentityObserver observer) {
    this.minecraftIdentityObserver = Objects.requireNonNull(observer, "observer");
  }

  public void bindMinecraftIdentityRollback(java.util.function.Consumer<UUID> rollback) {
    this.minecraftIdentityRollback = Objects.requireNonNull(rollback, "rollback");
  }

  public void observeTrustedMinecraftIdentity(
      UUID uuid, String username, IdentitySource trustedSource) {
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(username, "username");
    if (!isTrustedMigrationSource(trustedSource)) {
      throw new IllegalArgumentException("A trusted Minecraft identity source is required");
    }
    this.observeMinecraftIdentity(uuid, username, trustedSource);
  }

    @FunctionalInterface
    public interface MinecraftIdentityObserver {
        void accept(UUID uuid, String username, IdentitySource trustedSource);
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
        this.usernameResolver = userRepository::findFullByUsername;
        this.accountErasure = userRepository::delete;
        this.sessionManager.addExpirationListener(this::cleanupExpiredSession);
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
        Optional<StarxUser> connectedUser = this.resolveConnectedUser(uuid);
        if (connectedUser.isEmpty()) {
            return false;
        }
        UUID accountUuid = connectedUser.get().uuid();

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
                this.knownMinecraftUuids(accountUuid), ipAddress, deviceId, ipBypassMinutes)) {
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
        try {
            ipSessionStore.save(session);
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Login history persistence failed for " + uuid, error);
        }
    }

    /**
     * 获取玩家的最新登录会话
     */
    public Optional<IpSession> getLatestLoginSession(UUID uuid) {
        if (ipSessionStore == null) {
            return Optional.empty();
        }
        return ipSessionStore.findLatestByUuid(this.knownMinecraftUuids(uuid));
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

    public void setTotpAvailable(boolean enabled) {
        this.totpAvailable = enabled;
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
        ProvisionedLogin previous = this.provisionedLogins.get(uuid);
        if (previous != null
            && !this.cleanupProvisionedLogin(uuid, previous.lease(), false)) {
            return false;
        }
        return this.sessionManager.open(uuid, username, address, deviceId, lease) != null;
    }

    public boolean closeConnection(UUID uuid, AuthLease lease) {
        boolean removed = this.sessionManager.remove(uuid, lease);
        this.cleanupProvisionedLogin(uuid, lease, false);
        return removed;
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
        Optional<AuthSession> currentSession = this.sessionManager.get(uuid, lease);
        if (currentSession.isEmpty()
            || currentSession.get().state() != AuthSession.State.GUEST) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        String sessionUsername = currentSession.get().username();
        Optional<StarxUser> optional = this.resolveSessionUser(uuid, lease);
        if (optional.isEmpty()) {
            return AuthResult.failure("请先注册游戏账号，再使用网站登录");
        }
        StarxUser user = optional.get();
        if (!this.userRepository.hasTrustedWebsiteBinding(user.uuid(), sessionUsername)) {
            return AuthResult.failure("请先完成网站绑定，再使用网站登录");
        }
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.WEB_APPROVAL_PENDING)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }

        try {
            String approvalUrl = Objects.requireNonNull(this.webLoginApprovals, "webLoginApprovals")
                .request(uuid, sessionUsername, lease);
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
        source = source.trim();
        if (EXTERNAL_HANDSHAKE_SOURCE.equalsIgnoreCase(source)) {
            return AuthResult.failure(EXTERNAL_HANDSHAKE_IDENTITY_ERROR);
        }
        if (!isAllowedTrustedLoginSource(source, premium)) {
            return AuthResult.failure("可信身份来源无效");
        }
        Optional<AuthSession> session = this.sessionManager.get(uuid, lease);
        if (session.isEmpty()) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        String sessionUsername = session.get().username();
        IdentitySource trustedSource = trustedIdentitySource(source, premium);
        Instant now = Instant.now();
        Optional<StarxUser> account = this.resolveConnectedUser(uuid);
        if (source.equalsIgnoreCase("website-binding")
            && (account.isEmpty()
                || !this.userRepository.hasTrustedWebsiteBinding(
                    account.get().uuid(), sessionUsername))) {
            return AuthResult.failure("未找到可信的网站绑定");
        }
        if (source.equalsIgnoreCase("ip-session")) {
            if (account.isEmpty() || this.ipSessionStore == null) {
                return AuthResult.failure("可信会话已失效");
            }
            InetAddress sessionAddress = session.get().address();
            String deviceId = session.get().deviceId();
            if (sessionAddress == null || !this.ipSessionStore.hasRecentSessionMinutes(
                this.knownMinecraftUuids(account.get().uuid()),
                sessionAddress.getHostAddress(), deviceId, this.ipBypassMinutes)) {
                return AuthResult.failure("可信会话已失效");
            }
        }
        if (account.isEmpty()) {
            if (this.usernameResolver == null) {
                return AuthResult.failure("可信身份校验暂不可用");
            }
            Optional<StarxUser> sameName = this.usernameResolver.apply(sessionUsername);
            if (sameName.isPresent()) {
                UUID offlineUuid = offlineUuid(sameName.get().username());
                if (!isTrustedMigrationSource(trustedSource)
                    || !sameName.get().uuid().equals(offlineUuid)
                    || trustedSource == IdentitySource.FLOODGATE && sameName.get().premium()) {
                    return AuthResult.failure("该用户名已注册，请使用原账号登录");
                }
                account = sameName;
            }
        }
        boolean created = false;
        if (account.isEmpty()) {
            this.userRepository.create(new StarxUser(
                     uuid,
                     sessionUsername,
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
            created = true;
        }
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED)) {
            if (created && !this.cleanupCreatedProvisionedAccount(
                    uuid, lease, sessionUsername, false)) {
                return AuthResult.failure("认证会话已过期，账户清理失败，请联系管理员");
            }
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        if (!created) {
            StarxUser existing = account.orElseThrow();
            boolean loginUpdated = false;
            boolean premiumUpdated = false;
            try {
                this.userRepository.updateLastLogin(existing.uuid(), now);
                loginUpdated = true;
                if (premium) {
                    this.userRepository.updatePremium(existing.uuid(), true);
                    premiumUpdated = true;
                }
            } catch (RuntimeException error) {
                if (loginUpdated) {
                    try {
                        boolean currentPremium = premiumUpdated ? premium : existing.premium();
                        if (!this.userRepository.restoreLoginMetadataIfCurrent(
                            existing.uuid(), now, currentPremium,
                            existing.lastLoginAt(), existing.premium())) {
                            error.addSuppressed(new IllegalStateException(
                                "Login metadata rollback skipped because the account changed concurrently"));
                        }
                    } catch (RuntimeException rollbackError) {
                        error.addSuppressed(rollbackError);
                    }
                }
                this.sessionManager.transition(
                    uuid, lease, AuthSession.State.AUTHENTICATED, AuthSession.State.GUEST);
                logger.log(Level.WARNING, "Trusted login persistence failed for " + sessionUsername, error);
                return AuthResult.failure("登录信息保存失败，请稍后重试");
            }
        }
        try {
            this.observeMinecraftIdentity(uuid, sessionUsername, trustedSource);
        } catch (RuntimeException error) {
            this.sessionManager.transition(
                uuid, lease, AuthSession.State.AUTHENTICATED, AuthSession.State.GUEST);
            if (created && !this.cleanupCreatedProvisionedAccount(
                    uuid, lease, sessionUsername, true)) {
                return AuthResult.failure("身份记录失败，账户清理失败，请联系管理员");
            }
            if (!created) {
                StarxUser existing = account.orElseThrow();
                boolean expectedPremium = premium || existing.premium();
                try {
                    if (!this.userRepository.restoreLoginMetadataIfCurrent(
                        existing.uuid(), now, expectedPremium,
                        existing.lastLoginAt(), existing.premium())) {
                        error.addSuppressed(new IllegalStateException(
                            "Login metadata rollback skipped because the account changed concurrently"));
                    }
                } catch (RuntimeException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            logger.log(Level.WARNING, "Minecraft identity persistence failed for " + sessionUsername, error);
            return AuthResult.failure("身份信息保存失败，请稍后重试");
        }
        if (created) {
            this.provisionedLogins.put(
                uuid, new ProvisionedLogin(uuid, lease, sessionUsername, true));
        }
        this.eventBus.publish("player:login:success", Map.of(
                "uuid", uuid,
                "username", sessionUsername,
                "source", source));
        return AuthResult.success("可信身份自动登录成功", AuthSession.State.AUTHENTICATED);
    }

    public void completeAuthenticatedProvisioning(UUID sessionUuid, AuthLease lease) {
        Objects.requireNonNull(sessionUuid, "sessionUuid");
        Objects.requireNonNull(lease, "lease");
        this.provisionedLogins.computeIfPresent(sessionUuid, (ignored, provisioned) ->
            provisioned.lease().equals(lease) ? null : provisioned);
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
        String normalizedEmail;
        try {
            normalizedEmail = AuthService.normalizeEmail(email);
        } catch (IllegalArgumentException error) {
            return AuthResult.failure("请输入有效的邮箱地址");
        }
        if (normalizedEmail != null && this.userRepository.findByEmail(normalizedEmail).isPresent()) {
            return AuthResult.failure("该邮箱已被其他账号绑定");
        }
        StarxUser user = new StarxUser(uuid, username, normalizedEmail, PasswordHasher.hash(password), null, false, Instant.now(), null, null, null, null, "local", "completed", null, null, null, null, 0L, null, false);
        try {
            this.userRepository.create(user);
        } catch (RuntimeException error) {
            if (normalizedEmail != null && this.userRepository.findByEmail(normalizedEmail).isPresent()) {
                return AuthResult.failure("该邮箱已被其他账号绑定");
            }
            throw error;
        }
        if (!this.sessionManager.transition(
                uuid, lease, AuthSession.State.GUEST, AuthSession.State.AUTHENTICATED)) {
            if (!this.rollbackProvisionedAccount(uuid, username, false)) {
                return AuthResult.failure("认证会话已过期，账户清理失败，请联系管理员");
            }
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        try {
            this.observeMinecraftIdentity(uuid, username, null);
        } catch (RuntimeException error) {
            this.sessionManager.transition(
                uuid, lease, AuthSession.State.AUTHENTICATED, AuthSession.State.GUEST);
            if (!this.rollbackProvisionedAccount(uuid, username, true)) {
                return AuthResult.failure("身份记录失败，账户清理失败，请联系管理员");
            }
            logger.log(Level.WARNING, "Minecraft identity persistence failed for " + username, error);
            return AuthResult.failure("身份信息保存失败，请稍后重试");
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
        if (!this.sessionManager.isState(uuid, lease, AuthSession.State.GUEST)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        Optional<AuthSession> session = this.sessionManager.get(uuid, lease);
        if (session.isEmpty()) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        String sessionUsername = session.get().username();
        BruteForceProtector.Check bruteForce = this.checkBruteForce(uuid);
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.LOCKED) {
            long sec = bruteForce.waitMs() / 1000L;
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef\u6b21\u6570\u8fc7\u591a\uff0c\u8bf7" + sec + "\u79d2\u540e\u518d\u8bd5");
        }
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.DELAYED) {
            long sec = bruteForce.waitMs() / 1000L;
            return AuthResult.failure("\u8bf7\u7b49\u5f85" + sec + "\u79d2\u540e\u518d\u8bd5");
        }
        if (this.uniauthConfig.enabled() && this.uniauthConfig.bridgeMode() && this.uniauthBridge != null) {
            logger.log(Level.FINE, "Using UniAuth bridge for user: {0}", sessionUsername);
            CompletableFuture<UniAuthBridge.BridgeResult> bridgeResultFuture = this.uniauthBridge.authenticate(uuid, sessionUsername, password);
            try {
                UniAuthBridge.BridgeResult bridgeResult = bridgeResultFuture.get();
                if (bridgeResult.success() && bridgeResult.user() != null) {
                    this.clearBruteForce(uuid);
                    if (bridgeResult.provisionedAccount()) {
                        this.provisionedLogins.put(
                            uuid,
                            new ProvisionedLogin(uuid, lease, bridgeResult.user().username(), false));
                    }
                    try {
                        return this.completePasswordAuthentication(
                            lease, uuid, bridgeResult.user(), totpCode, address, deviceId);
                    } catch (RuntimeException error) {
                        this.cleanupProvisionedLogin(uuid, lease, false);
                        logger.log(Level.WARNING, "UniAuth authentication completion failed", error);
                        return AuthResult.failure("认证暂时不可用，请稍后重试");
                    }
                }
                if (bridgeResult.serviceUnavailable()) {
                    this.publishLoginFailed(uuid, sessionUsername, bridgeResult.message());
                    return AuthResult.failure(bridgeResult.message());
                }
                this.recordBruteForceFailure(uuid);
                this.publishLoginFailed(uuid, sessionUsername, bridgeResult.message());
                return AuthResult.failure(bridgeResult.message());
            }
            catch (Exception e) {
                logger.log(Level.WARNING, "UniAuth bridge failed", e);
                return AuthResult.failure("认证服务暂时不可用，请稍后重试");
            }
        }
        return this.loginLocal(lease, uuid, sessionUsername, password, totpCode, address, deviceId);
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
        Optional<StarxUser> optional = this.resolveSessionUser(uuid, lease);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            this.recordBruteForceFailure(uuid);
            int failures = this.bruteForceAttemptCount(uuid);
            if (failures >= 3) {
                this.eventBus.publish("player:brute-force", Map.of("uuid", uuid, "username", username, "attempts", failures, "ip", address == null ? "unknown" : address.getHostAddress()));
            }
            this.publishLoginFailed(uuid, username, "\u5bc6\u7801\u9519\u8bef");
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        return this.completePasswordAuthentication(
            lease, uuid, user, totpCode, address, deviceId);
    }

    private AuthResult completePasswordAuthentication(
        AuthLease lease,
        UUID uuid,
        StarxUser user,
        String totpCode,
        InetAddress address,
        String deviceId
    ) {
        String sessionUsername = this.sessionManager.get(uuid, lease)
            .map(AuthSession::username).orElse(user.username());
        RiskDecisionEngine.Decision loginDecision =
            this.loginDecision(uuid, user, deviceId, address);
        if (loginDecision.action() != RiskDecisionEngine.Action.ALLOW) {
            if (loginDecision.action() == RiskDecisionEngine.Action.REQUIRE_TOTP
                && totpCode != null
                && TotpGenerator.verify(user.totpSecret(), totpCode, Instant.now())) {
                return this.authenticate(uuid, user, lease, AuthSession.State.GUEST);
            }
            AuthSession.State pendingState =
                loginDecision.action() == RiskDecisionEngine.Action.REQUIRE_WEB_APPROVAL
                    ? AuthSession.State.WEB_APPROVAL_PENDING
                    : AuthSession.State.AUTHENTICATING;
            if (!this.sessionManager.transition(
                    uuid, lease, AuthSession.State.GUEST, pendingState)) {
                this.cleanupProvisionedLogin(uuid, lease, false);
                return AuthResult.failure("认证会话已过期，请重新连接。");
            }
            if (pendingState == AuthSession.State.WEB_APPROVAL_PENDING) {
                try {
                    String url = Objects.requireNonNull(this.webLoginApprovals, "webLoginApprovals")
                        .request(uuid, sessionUsername, lease);
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
                    this.cleanupProvisionedLogin(uuid, lease, false);
                    logger.log(Level.WARNING, "Unable to create web login approval", error);
                    return AuthResult.failure("网页确认暂不可用，请稍后重试");
                }
            }
            return AuthResult.success("\u8bf7\u8f93\u5165\u4e8c\u6b65\u9a8c\u8bc1\u7801", AuthSession.State.AUTHENTICATING);
        }
        this.clearBruteForce(uuid);
        return this.authenticate(uuid, user, lease, AuthSession.State.GUEST);
    }

    public AuthResult verifyTotp(AuthLease lease, UUID uuid, String code) {
        if (!this.sessionManager.isState(
                uuid, lease, AuthSession.State.AUTHENTICATING)) {
            return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
        }
        BruteForceProtector.Check bruteForce = this.checkBruteForce(uuid);
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.LOCKED) {
            return AuthResult.failure("\u5c1d\u8bd5\u6b21\u6570\u8fc7\u591a\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5");
        }
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.DELAYED) {
            long seconds = Math.max(1L, (bruteForce.waitMs() + 999L) / 1000L);
            return AuthResult.failure("\u8bf7\u7b49\u5f85" + seconds + "\u79d2\u540e\u518d\u8bd5");
        }
        Optional<StarxUser> optional = this.resolveSessionUser(uuid, lease);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (user.totpSecret() == null || user.totpSecret().isBlank()) {
            return AuthResult.failure("\u672a\u5f00\u542f\u4e8c\u6b65\u9a8c\u8bc1");
        }
        if (code == null || !code.matches("\\d{6}")
            || !TotpGenerator.verify(user.totpSecret(), code, Instant.now())) {
            this.recordBruteForceFailure(uuid);
            this.publishLoginFailed(uuid, user.username(), "\u4e8c\u6b65\u9a8c\u8bc1\u7801\u9519\u8bef");
            return AuthResult.failure("\u4e8c\u6b65\u9a8c\u8bc1\u7801\u9519\u8bef");
        }
        return this.authenticate(uuid, user, lease, AuthSession.State.AUTHENTICATING);
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
        StarxUser user = this.resolveSessionUser(uuid, lease).orElse(null);
        if (user == null) {
            return AuthResult.failure("用户未注册");
        }
        return this.authenticate(uuid, user, lease, AuthSession.State.WEB_APPROVAL_PENDING);
    }

    public AuthResult logout(UUID uuid) {
        Optional<AuthSession> session = this.sessionManager.get(uuid);
        String username = session.map(AuthSession::username).orElse("");
        this.sessionManager.remove(uuid);
        ProvisionedLogin provisioned = this.provisionedLogins.get(uuid);
        if (provisioned != null) {
            this.cleanupProvisionedLogin(uuid, provisioned.lease(), false);
        }
        this.eventBus.publish("player:logout", Map.of("uuid", uuid, "username", username));
        return AuthResult.success("\u5df2\u767b\u51fa");
    }

    public AuthResult changePassword(UUID uuid, String oldPassword, String newPassword) {
        Optional<StarxUser> optional = this.resolveConnectedUser(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (!PasswordHasher.verify(oldPassword, user.passwordHash())) {
            return AuthResult.failure("\u539f\u5bc6\u7801\u9519\u8bef");
        }
        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) return AuthResult.failure(passwordError);
        return this.changePasswordAndRevokeTrust(user, newPassword, "\u5bc6\u7801\u4fee\u6539\u6210\u529f");
    }

    private static final int TRUSTED_DEVICE_UPDATE_RETRIES = 3;

    public AuthResult addTrustedDevice(UUID uuid, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return AuthResult.failure("\u8bbe\u5907\u6807\u8bc6\u4e0d\u80fd\u4e3a\u7a7a");
        }
        for (int attempt = 0; attempt < TRUSTED_DEVICE_UPDATE_RETRIES; attempt++) {
            Optional<StarxUser> optional = this.resolveConnectedUser(uuid);
            if (optional.isEmpty()) {
                return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
            }
            StarxUser user = optional.get();
            ArrayList<String> devices = new ArrayList<>(user.trustedDevices());
            if (devices.contains(deviceId)) {
                return AuthResult.success("\u8bbe\u5907\u5df2\u4fe1\u4efb");
            }
            devices.add(deviceId);
            if (this.userRepository.replaceTrustedDevicesIfCurrent(
                user.uuid(), user.trustedDevices(), devices)) {
                return AuthResult.success("\u8bbe\u5907\u5df2\u6dfb\u52a0\u4fe1\u4efb");
            }
        }
        return AuthResult.failure("\u8bbe\u5907\u4fe1\u4efb\u72b6\u6001\u5df2\u66f4\u65b0\uff0c\u8bf7\u91cd\u8bd5");
    }

    public AuthResult removeTrustedDevice(UUID uuid, String deviceId) {
        for (int attempt = 0; attempt < TRUSTED_DEVICE_UPDATE_RETRIES; attempt++) {
            Optional<StarxUser> optional = this.resolveConnectedUser(uuid);
            if (optional.isEmpty()) {
                return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
            }
            StarxUser user = optional.get();
            ArrayList<String> devices = new ArrayList<>(user.trustedDevices());
            if (!devices.remove(deviceId)) {
                return AuthResult.failure("\u8bbe\u5907\u4e0d\u5728\u4fe1\u4efb\u5217\u8868\u4e2d");
            }
            if (this.userRepository.replaceTrustedDevicesIfCurrent(
                user.uuid(), user.trustedDevices(), devices)) {
                return AuthResult.success("\u8bbe\u5907\u5df2\u53d6\u6d88\u4fe1\u4efb");
            }
        }
        return AuthResult.failure("\u8bbe\u5907\u4fe1\u4efb\u72b6\u6001\u5df2\u66f4\u65b0\uff0c\u8bf7\u91cd\u8bd5");
    }

    public boolean isTrustedDevice(UUID uuid, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return this.resolveConnectedUser(uuid)
            .flatMap(user -> this.userRepository.findTrustedDevicesByUuid(user.uuid()))
            .map(json -> {
            List<String> devices = AuthService.parseTrustedDevices(json);
            return devices.contains(deviceId);
        }).orElse(false);
    }

    public boolean isUserRegistered(UUID uuid) {
        return this.resolveConnectedUser(uuid).isPresent();
    }

    public Optional<StarxUser> findConnectedUser(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return this.resolveConnectedUser(uuid);
    }

    public boolean hasTrustedWebsiteBinding(UUID connectionUuid, String username) {
        if (username == null || username.isBlank()) return false;
        return this.resolveConnectedUser(connectionUuid)
            .map(user -> this.userRepository.hasTrustedWebsiteBinding(user.uuid(), username.trim()))
            .orElse(false);
    }

    public boolean isUserRegistered(UUID uuid, String username) {
        return this.isUserRegistered(uuid);
    }

    public Optional<AuthSession.State> getSessionState(UUID uuid) {
        return this.sessionManager.get(uuid).map(AuthSession::state);
    }

    public boolean cancelAuthentication(UUID uuid, AuthLease lease) {
        boolean removed = this.sessionManager.removeIfState(
            uuid, lease, AuthSession.State.AUTHENTICATING)
            || this.sessionManager.removeIfState(
                uuid, lease, AuthSession.State.WEB_APPROVAL_PENDING)
            || this.sessionManager.removeIfState(
                uuid, lease, AuthSession.State.AUTHENTICATED);
        this.cleanupProvisionedLogin(uuid, lease, false);
        return removed;
    }

    public boolean isTotpEnabled(UUID uuid) {
        return this.resolveConnectedUser(uuid)
            .map(StarxUser::totpSecret)
            .filter(secret -> secret != null && !secret.isBlank())
            .isPresent();
    }

    public AuthResult verifyRecoveryCode(AuthLease lease, UUID uuid, String recoveryCode) {
        if (recoveryCode == null || recoveryCode.isBlank()) {
            if (this.sessionManager.isState(uuid, lease, AuthSession.State.AUTHENTICATING)) {
                this.recordBruteForceFailure(uuid);
            }
            return AuthResult.failure("\u6062\u590d\u7801\u65e0\u6548");
        }
        String candidate = recoveryCode.trim();
        if (!this.sessionManager.isState(
                uuid, lease, AuthSession.State.AUTHENTICATING)) {
            return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
        }
        BruteForceProtector.Check bruteForce = this.checkBruteForce(uuid);
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.LOCKED) {
            return AuthResult.failure("\u5c1d\u8bd5\u6b21\u6570\u8fc7\u591a\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5");
        }
        if (bruteForce.status() == BruteForceProtector.BruteForceStatus.DELAYED) {
            long seconds = Math.max(1L, (bruteForce.waitMs() + 999L) / 1000L);
            return AuthResult.failure("\u8bf7\u7b49\u5f85" + seconds + "\u79d2\u540e\u518d\u8bd5");
        }

        for (int attempt = 0; attempt < RECOVERY_CODE_UPDATE_ATTEMPTS; attempt++) {
            if (!this.sessionManager.isState(
                    uuid, lease, AuthSession.State.AUTHENTICATING)) {
                return AuthResult.failure("\u8bf7\u5148\u767b\u5f55");
            }
            Optional<StarxUser> optional = this.resolveSessionUser(uuid, lease);
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
                this.recordBruteForceFailure(uuid);
                return AuthResult.failure("\u6062\u590d\u7801\u65e0\u6548");
            }

            List<String> remaining = new ArrayList<>(hashes);
            remaining.remove(matchedIndex);
            String replacement = encodeRecoveryCodeHashes(remaining);
            if (this.userRepository.replaceRecoveryCodes(user.uuid(), stored, replacement)) {
                AuthResult result = this.authenticate(uuid, user, lease, AuthSession.State.AUTHENTICATING);
                if (!result.success()) {
                    try {
                        if (!this.userRepository.restoreRecoveryCodesIfCurrent(
                            user.uuid(), replacement, stored)) {
                            result = AuthResult.failure(
                                result.message() + "，恢复码状态已变化，请联系管理员");
                        }
                    } catch (RuntimeException rollbackError) {
                        result = AuthResult.failure(
                            result.message() + "，恢复码回滚失败，请联系管理员");
                    }
                }
                return result;
            }
        }
        return AuthResult.failure("\u6062\u590d\u7801\u5df2\u66f4\u65b0\uff0c\u8bf7\u91cd\u8bd5");
    }

    public BruteForceProtector bruteForceProtector() {
        return this.bruteForceProtector;
    }

    private BruteForceProtector.Check checkBruteForce(UUID uuid) {
        BruteForceProtector.Check strongest = new BruteForceProtector.Check(
            BruteForceProtector.BruteForceStatus.ALLOWED, 0);
        for (UUID key : this.bruteForceKeys(uuid)) {
            BruteForceProtector.Check current = this.bruteForceProtector.check(key);
            if (current.status().ordinal() > strongest.status().ordinal()
                || current.status() == strongest.status()
                    && current.waitMs() > strongest.waitMs()) {
                strongest = current;
            }
        }
        return strongest;
    }

    private void recordBruteForceFailure(UUID uuid) {
        for (UUID key : this.bruteForceKeys(uuid)) {
            this.bruteForceProtector.recordFailure(key);
        }
    }

    private int bruteForceAttemptCount(UUID uuid) {
        return this.bruteForceKeys(uuid).stream()
            .mapToInt(this.bruteForceProtector::getAttemptCount)
            .max()
            .orElse(0);
    }

    private void clearBruteForce(UUID uuid) {
        for (UUID key : this.bruteForceKeys(uuid)) {
            this.bruteForceProtector.clear(key);
        }
    }

    private Set<UUID> bruteForceKeys(UUID uuid) {
        Set<UUID> keys = new java.util.LinkedHashSet<>(this.knownMinecraftUuids(uuid));
        keys.add(uuid);
        keys.remove(null);
        return keys;
    }

    AuthResult enableTotp(UUID uuid, String password) {
        StarxUser user = this.resolveConnectedUser(uuid).orElse(null);
        if (user == null) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        String secret = TotpGenerator.generateSecret();
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashedCodes = recoveryCodes.stream()
            .map(PasswordHasher::hash)
            .toList();
        String encodedRecoveryCodes = encodeRecoveryCodeHashes(hashedCodes);
        if (!this.userRepository.enableTotp(
                user.uuid(), secret, encodedRecoveryCodes)) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        try {
            this.revokeSecurityTrust(user.uuid(), uuid, "totp-enabled", user.username());
        } catch (RuntimeException error) {
            this.rollbackTotpEnable(user.uuid(), secret, encodedRecoveryCodes, error);
            throw error;
        }
        this.eventBus.publish("player:totp:enabled", Map.of("uuid", user.uuid(), "method", "direct"));
        return AuthResult.totpEnabled(secret, recoveryCodes);
    }

    public TotpEnrollment beginTotpEnrollment(UUID uuid, String password) {
        StarxUser user = this.resolveConnectedUser(uuid)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        if (user.totpSecret() != null && !user.totpSecret().isBlank()) {
            throw new IllegalStateException("二步验证已开启");
        }
        String secret = TotpGenerator.generateSecret();
        Instant expiresAt = Instant.now().plus(TOTP_ENROLLMENT_TTL);
        this.pendingTotp.put(user.uuid(), new PendingTotp(user.uuid(), secret, expiresAt));
        return new TotpEnrollment(
            secret,
            TotpProvisioning.uri("StarMC", user.username(), secret).toString(),
            expiresAt);
    }

    public AuthResult confirmTotpEnrollment(UUID uuid, String code) {
        Optional<StarxUser> connectedUser = this.resolveConnectedUser(uuid);
        UUID accountUuid = connectedUser.map(StarxUser::uuid).orElse(uuid);
        String connectedUsername = connectedUser.map(StarxUser::username).orElse(null);
        PendingTotp pending = this.pendingTotp.get(accountUuid);
        if (pending == null || !pending.expiresAt().isAfter(Instant.now())) {
            this.pendingTotp.remove(accountUuid);
            return AuthResult.failure("二步验证设置已过期，请重新开始");
        }
        if (code == null || !code.matches("\\d{6}")
            || !TotpGenerator.verify(pending.secret(), code, Instant.now())) {
            return AuthResult.failure("验证码错误");
        }
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashedCodes = recoveryCodes.stream().map(PasswordHasher::hash).toList();
        String encodedRecoveryCodes = encodeRecoveryCodeHashes(hashedCodes);
        if (!this.userRepository.enableTotp(
                pending.accountUuid(), pending.secret(), encodedRecoveryCodes)) {
            return AuthResult.failure("用户不存在");
        }
        try {
            this.revokeSecurityTrust(
                pending.accountUuid(), uuid, "totp-enabled", connectedUsername);
        } catch (RuntimeException error) {
            this.rollbackTotpEnable(
                pending.accountUuid(), pending.secret(), encodedRecoveryCodes, error);
            throw error;
        }
        this.pendingTotp.remove(accountUuid, pending);
        this.eventBus.publish("player:totp:enabled", Map.of(
            "uuid", pending.accountUuid(), "method", "confirmed_enrollment"));
        return AuthResult.totpEnabled(pending.secret(), recoveryCodes);
    }

    public AuthResult rotateRecoveryCodes(UUID uuid, String code) {
        StarxUser user = this.resolveConnectedUser(uuid).orElse(null);
        if (user == null || user.totpSecret() == null || user.totpSecret().isBlank()) {
            return AuthResult.failure("二步验证未开启");
        }
        if (code == null || !TotpGenerator.verify(user.totpSecret(), code.trim(), Instant.now())) {
            return AuthResult.failure("验证码错误");
        }
        List<String> recoveryCodes = RecoveryCodeGenerator.generate();
        List<String> hashes = recoveryCodes.stream().map(PasswordHasher::hash).toList();
        if (!this.userRepository.replaceRecoveryCodes(
                user.uuid(), user.recoveryCodes(), encodeRecoveryCodeHashes(hashes))) {
            return AuthResult.failure("恢复码已更新，请重试");
        }
        return AuthResult.recoveryCodesRotated(recoveryCodes);
    }

    public AuthResult disableTotp(UUID uuid, String password) {
        Optional<StarxUser> optional = this.resolveConnectedUser(uuid);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u672a\u6ce8\u518c");
        }
        StarxUser user = optional.get();
        if (!PasswordHasher.verify(password, user.passwordHash())) {
            return AuthResult.failure("\u5bc6\u7801\u9519\u8bef");
        }
        if (user.totpSecret() == null || user.totpSecret().isBlank()) {
            return AuthResult.failure("\u4e8c\u6b65\u9a8c\u8bc1\u672a\u5f00\u542f");
        }
        if (!this.userRepository.disableTotp(user.uuid())) {
            return AuthResult.failure("二步验证状态已更新，请重试");
        }
        try {
            this.revokeSecurityTrust(user.uuid(), uuid, "totp-disabled", user.username());
        } catch (RuntimeException error) {
            this.rollbackTotpDisable(user, error);
            throw error;
        }
        this.pendingTotp.remove(user.uuid());
        this.eventBus.publish("player:totp:disabled", Map.of("uuid", user.uuid(), "username", user.username()));
        return AuthResult.success("\u4e8c\u6b65\u9a8c\u8bc1\u5df2\u5173\u95ed");
    }

    public AuthResult resetPassword(String username, String newPassword) {
        Optional<StarxUser> optional = this.usernameResolver.apply(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) return AuthResult.failure(passwordError);
        return this.changePasswordAndRevokeTrust(optional.get(), newPassword, "\u5bc6\u7801\u5df2\u91cd\u7f6e");
    }

    private AuthResult changePasswordAndRevokeTrust(
        StarxUser user, String newPassword, String successMessage
    ) {
        String replacementHash = PasswordHasher.hash(newPassword);
        try {
            this.userRepository.markPasswordMigrated(
                user.uuid(), replacementHash, Instant.now());
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Password update failed for " + user.username(), error);
            return AuthResult.failure("\u5bc6\u7801\u66f4\u65b0\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        try {
            this.revokeCredentialTrust(user);
            return AuthResult.success(successMessage);
        } catch (RuntimeException error) {
            try {
                if (!this.userRepository.restorePasswordMigrationIfCurrent(
                    user.uuid(), replacementHash, user.passwordHash(),
                    user.migrationState(), user.passwordMigratedAt())) {
                    error.addSuppressed(new IllegalStateException(
                        "Password rollback skipped because the account changed concurrently"));
                }
            } catch (RuntimeException restoreError) {
                error.addSuppressed(restoreError);
            }
            throw error;
        }
    }

    private void revokeCredentialTrust(StarxUser user) {
        Set<UUID> knownUuids = this.knownMinecraftUuids(user.uuid());
        List<UUID> connectionIds = this.sessionManager.sessionIdsForUuids(knownUuids);
        this.revokeSecurityTrust(user.uuid(), null, "password-changed", user.username());
        if (connectionIds.isEmpty()) {
            this.eventBus.publish("player:credentials:changed", Map.of("uuid", user.uuid()));
            return;
        }
        connectionIds.forEach(connectionId -> this.eventBus.publish(
            "player:credentials:changed", Map.of("uuid", connectionId)));
    }

    private void revokeSecurityTrust(
        UUID playerId, UUID retainedSessionId, String reason, String username) {
        Set<UUID> knownUuids = this.knownMinecraftUuids(playerId);
        if (this.ipSessionStore != null) {
            this.ipSessionStore.deleteByUuid(knownUuids);
        }
        if (this.trustedDevices != null) {
            this.trustedDevices.revokeAll(knownUuids, Instant.now());
        }
        this.userRepository.updateTrustedDevices(playerId, List.of());
        java.util.LinkedHashSet<UUID> sessionIds = new java.util.LinkedHashSet<>(
            this.sessionManager.sessionIdsForUuids(knownUuids));
        if (username != null && !username.isBlank()) {
            sessionIds.addAll(this.sessionManager.sessionIdsForUsername(username));
        }
        List<UUID> revokedSessionUuids = sessionIds.stream()
            .filter(sessionId -> !sessionId.equals(retainedSessionId)).toList();
        for (UUID sessionId : revokedSessionUuids) {
            if (!sessionId.equals(retainedSessionId)) this.sessionManager.remove(sessionId);
        }
        this.pendingTotp.remove(playerId);
        this.clearBruteForce(playerId);
        this.eventBus.publish("player:security:changed", Map.of(
            "uuid", playerId,
            "reason", reason,
            "sessionRetained", retainedSessionId != null,
            "disconnectSessions", reason.startsWith("totp-"),
            "revokedSessionUuids", revokedSessionUuids));
    }

    private void rollbackTotpEnable(
        UUID playerId, String secret, String recoveryCodes, RuntimeException failure) {
        try {
            if (!this.userRepository.disableTotpIfCurrent(playerId, secret, recoveryCodes)) {
                failure.addSuppressed(new IllegalStateException("TOTP rollback did not update the user"));
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void rollbackTotpDisable(StarxUser user, RuntimeException failure) {
        try {
            if (!this.userRepository.restoreTotpIfCurrentDisabled(
                    user.uuid(), user.totpSecret(), user.recoveryCodes())) {
                failure.addSuppressed(new IllegalStateException("TOTP rollback did not update the user"));
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private boolean rollbackProvisionedAccount(UUID uuid, String username, boolean identityObserved) {
        boolean clean = true;
        try {
            this.userRepository.delete(uuid);
        } catch (RuntimeException error) {
            logger.log(Level.SEVERE, "Unable to clean up provisioned account for " + username, error);
            clean = false;
        }
        if (identityObserved) {
            try {
                this.minecraftIdentityRollback.accept(uuid);
            } catch (RuntimeException error) {
                logger.log(Level.SEVERE, "Unable to clean up Minecraft identity for " + username, error);
                clean = false;
            }
        }
        return clean;
    }

    private boolean cleanupProvisionedLogin(UUID sessionUuid, AuthLease lease, boolean identityObserved) {
        ProvisionedLogin provisioned = this.provisionedLogins.get(sessionUuid);
        if (provisioned == null || !provisioned.lease().equals(lease)) {
            return true;
        }
        boolean observed = provisioned.identityObserved() || identityObserved;
        boolean clean = this.rollbackProvisionedAccount(
            provisioned.accountUuid(), provisioned.username(), observed);
        if (clean) {
            this.provisionedLogins.remove(sessionUuid, provisioned);
        } else {
            this.provisionedLogins.replace(
                sessionUuid,
                provisioned,
                new ProvisionedLogin(
                    provisioned.accountUuid(), provisioned.lease(), provisioned.username(), observed));
            logger.log(Level.SEVERE,
                "Provisioned account cleanup will be retried for " + provisioned.username());
        }
        return clean;
    }

    private boolean cleanupCreatedProvisionedAccount(
        UUID accountUuid, AuthLease lease, String username, boolean identityObserved) {
        ProvisionedLogin marker = new ProvisionedLogin(
            accountUuid, lease, username, identityObserved);
        this.provisionedLogins.put(accountUuid, marker);
        return this.cleanupProvisionedLogin(accountUuid, lease, identityObserved);
    }

    private void cleanupExpiredSession(AuthSession session) {
        this.cleanupProvisionedLogin(session.uuid(), session.lease(), false);
    }

    public AuthResult bindEmail(String username, String email) {
        Optional<StarxUser> optional = this.usernameResolver.apply(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        return bindEmail(optional.get(), email);
    }

    public AuthResult bindEmail(UUID uuid, String email) {
        Optional<StarxUser> optional = this.resolveConnectedUser(uuid);
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
        Optional<StarxUser> optional = this.usernameResolver.apply(username);
        if (optional.isEmpty()) {
            return AuthResult.failure("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        StarxUser user = optional.get();
        UUID accountUuid = user.uuid();
        Set<UUID> sessions;
        try {
            sessions = new java.util.LinkedHashSet<>(
                this.knownMinecraftUuids(accountUuid));
            sessions.addAll(this.sessionManager.sessionIdsForUsername(user.username()));
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Unable to resolve sessions before erasing " + accountUuid, error);
            return AuthResult.failure("\u7528\u6237\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        try {
            this.accountErasure.accept(accountUuid);
        } catch (RuntimeException error) {
            logger.log(Level.WARNING, "Account erasure failed for " + accountUuid, error);
            return AuthResult.failure("\u7528\u6237\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        for (UUID sessionUuid : sessions) {
            this.sessionManager.remove(sessionUuid);
            this.provisionedLogins.remove(sessionUuid);
        }
        return AuthResult.success("\u7528\u6237\u5df2\u5220\u9664");
    }

    private AuthResult authenticate(
        StarxUser user,
        AuthLease lease,
        AuthSession.State expected
    ) {
        return this.authenticate(user.uuid(), user, lease, expected);
    }

    private AuthResult authenticate(
        UUID sessionUuid,
        StarxUser user,
        AuthLease lease,
        AuthSession.State expected
    ) {
        if (!this.sessionManager.isState(sessionUuid, lease, expected)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
    if (!this.sessionManager.transition(
                sessionUuid, lease, expected, AuthSession.State.AUTHENTICATED)) {
            return AuthResult.failure("认证会话已过期，请重新连接。");
        }
        Instant loginAt = Instant.now();
            try {
                this.userRepository.updateLastLogin(user.uuid(), loginAt);
            } catch (RuntimeException error) {
            this.sessionManager.transition(
                sessionUuid, lease, AuthSession.State.AUTHENTICATED, expected);
            boolean clean = this.cleanupProvisionedLogin(sessionUuid, lease, false);
            logger.log(Level.WARNING, "Login persistence failed for " + user.username(), error);
            if (!clean) {
                return AuthResult.failure("登录信息保存失败，账户清理失败，请联系管理员");
            }
            return AuthResult.failure("登录信息保存失败，请稍后重试");
        }
        try {
            this.observeMinecraftIdentity(sessionUuid, user.username(), null);
        } catch (RuntimeException error) {
            this.sessionManager.transition(
                sessionUuid, lease, AuthSession.State.AUTHENTICATED, expected);
            boolean clean = this.cleanupProvisionedLogin(sessionUuid, lease, true);
            try {
                if (!this.userRepository.restoreLoginMetadataIfCurrent(
                    user.uuid(), loginAt,
                    user.premium(), user.lastLoginAt(), user.premium())) {
                    error.addSuppressed(new IllegalStateException(
                        "Login metadata rollback skipped because the account changed concurrently"));
                }
            } catch (RuntimeException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            logger.log(Level.WARNING, "Minecraft identity persistence failed for " + user.username(), error);
            if (!clean) {
                return AuthResult.failure("身份记录失败，账户清理失败，请联系管理员");
            }
            return AuthResult.failure("身份信息保存失败，请稍后重试");
        }
        this.clearBruteForce(sessionUuid);
        this.clearBruteForce(user.uuid());
        this.rememberTrustedDevice(user.uuid(), sessionUuid, lease);
        this.sessionManager.get(sessionUuid, lease)
            .map(AuthSession::address)
            .filter(Objects::nonNull)
            .map(InetAddress::getHostAddress)
            .ifPresent(address -> this.recordSuccessfulLogin(
                user.uuid(), address, "local", this.sessionDeviceId(sessionUuid, lease)));
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
            Set<UUID> knownUuids = this.knownMinecraftUuids(user.uuid());
            return this.trustedDevices.isTrusted(knownUuids, deviceId, region, now)
                || this.trustedDevices.hasFamiliarRegion(knownUuids, region, now);
        }
        return user.trustedDevices() != null && user.trustedDevices().contains(deviceId);
    }

    private RiskDecisionEngine.Decision loginDecision(
        UUID sessionUuid, StarxUser user, String deviceId, InetAddress address) {
        Instant now = Instant.now();
        boolean trustedDevice;
        boolean familiarRegion;
        if (this.trustedDevices != null) {
            String region = address == null ? "unknown" : RegionKey.from(address);
            Set<UUID> knownUuids = this.knownMinecraftUuids(user.uuid());
            trustedDevice = deviceId != null && !deviceId.isBlank()
                && this.trustedDevices.isTrusted(knownUuids, deviceId, region, now);
            familiarRegion = this.trustedDevices.hasFamiliarRegion(knownUuids, region, now);
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
                this.bruteForceAttemptCount(sessionUuid),
                this.webLoginApprovals != null,
                this.userRepository.hasTrustedWebsiteBinding(
                    user.uuid(),
                    this.sessionManager.get(sessionUuid)
                        .map(AuthSession::username)
                        .orElse(user.username())),
                this.totpAvailable));
        if (decision.action() != RiskDecisionEngine.Action.ALLOW) {
            this.eventBus.publish("player:login:risk-step-up", Map.of(
                "uuid", sessionUuid,
                "score", decision.score(),
                "action", decision.action().name(),
                "reasons", decision.reasons()));
        }
        return decision;
    }

    private Optional<StarxUser> resolveSessionUser(UUID connectionUuid, AuthLease lease) {
        return this.sessionManager.get(connectionUuid, lease)
            .flatMap(session -> this.resolveAccount(session.username(), connectionUuid));
    }

    private Optional<StarxUser> resolveConnectedUser(UUID connectionUuid) {
        Optional<StarxUser> bySession = this.sessionManager.get(connectionUuid)
            .flatMap(session -> this.resolveAccount(session.username(), connectionUuid));
        if (bySession.isPresent()) return bySession;
        for (UUID knownUuid : this.knownMinecraftUuids(connectionUuid)) {
            Optional<StarxUser> byUuid = this.userRepository.findFullByUuid(knownUuid);
            if (byUuid.isPresent()) return byUuid;
        }
        return Optional.empty();
    }

    private Optional<StarxUser> resolveAccount(String username, UUID connectionUuid) {
    Optional<StarxUser> byUuid = this.userRepository.findFullByUuid(connectionUuid);
    if (byUuid.isPresent()) {
        return byUuid;
    }
    for (UUID knownUuid : this.knownMinecraftUuids(connectionUuid)) {
        if (knownUuid.equals(connectionUuid)) continue;
        Optional<StarxUser> byAlias = this.userRepository.findFullByUuid(knownUuid);
        if (byAlias.isPresent()) return byAlias;
    }
    if (username != null && !username.isBlank()) {
            Optional<StarxUser> byUsername = this.usernameResolver.apply(username);
            if (byUsername.isPresent()) {
                UUID offlineUuid = offlineUuid(byUsername.get().username());
                if (byUsername.get().uuid().equals(offlineUuid)
                        && connectionUuid.equals(offlineUuid)) {
                    return byUsername;
                }
            }
        }
        return Optional.empty();
    }

    private void rememberTrustedDevice(UUID playerId, AuthLease lease) {
        this.rememberTrustedDevice(playerId, playerId, lease);
    }

    private void rememberTrustedDevice(UUID playerId, UUID sessionUuid, AuthLease lease) {
        if (this.trustedDevices == null) return;
        String deviceId = this.sessionDeviceId(sessionUuid, lease);
        if (deviceId == null || deviceId.isBlank()) return;
        this.sessionManager.get(sessionUuid, lease)
            .map(AuthSession::address)
            .filter(Objects::nonNull)
            .ifPresent(address -> {
                try {
                    this.trustedDevices.observe(
                        playerId,
                        deviceId,
                        RegionKey.from(address),
                        "Minecraft client",
                        Instant.now().plus(Duration.ofDays(30)),
                        Instant.now());
                } catch (RuntimeException error) {
                    logger.log(Level.WARNING, "Trusted device persistence failed for " + playerId, error);
                }
            });
    }

    private String sessionDeviceId(UUID playerId, AuthLease lease) {
        return this.sessionManager.get(playerId, lease)
            .map(AuthSession::deviceId)
            .orElse(null);
    }

    private Set<UUID> knownMinecraftUuids(UUID requestedUuid) {
        Set<UUID> resolved = this.minecraftIdentityResolver.apply(requestedUuid);
        if (resolved == null || resolved.isEmpty()) return Set.of(requestedUuid);
        java.util.LinkedHashSet<UUID> known = new java.util.LinkedHashSet<>();
        known.add(requestedUuid);
        for (UUID uuid : resolved) known.add(Objects.requireNonNull(uuid, "resolved uuid"));
        return Set.copyOf(known);
    }

    private void observeMinecraftIdentity(
        UUID uuid, String username, IdentitySource trustedSource) {
        this.minecraftIdentityObserver.accept(uuid, username, trustedSource);
    }

    private static IdentitySource trustedIdentitySource(String source, boolean premium) {
        if (premium) return IdentitySource.MOJANG;
        return source.equalsIgnoreCase("floodgate") ? IdentitySource.FLOODGATE : null;
    }

    private static boolean isAllowedTrustedLoginSource(String source, boolean premium) {
        if (premium) return source.equalsIgnoreCase("premium");
        return source.equalsIgnoreCase("floodgate")
            || source.equalsIgnoreCase("ip-session")
            || source.equalsIgnoreCase("website-binding");
    }

    private static boolean isTrustedMigrationSource(IdentitySource source) {
        return source == IdentitySource.MOJANG || source == IdentitySource.FLOODGATE;
    }

    private void publishLoginFailed(UUID uuid, String username, String reason) {
        this.eventBus.publish("player:login:failed", Map.of("uuid", uuid, "username", username, "reason", reason));
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        try {
            return EmailAddress.normalize(email);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("请输入有效的邮箱地址", error);
        }
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
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

    private record PendingTotp(UUID accountUuid, String secret, Instant expiresAt) {
    }

    private record ProvisionedLogin(
        UUID accountUuid, AuthLease lease, String username, boolean identityObserved) {
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
