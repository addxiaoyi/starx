package io.github.addxiaoyi.starx.velocity.module.auth;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.auth.AuthCommandHandler;
import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.AuthResult;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.AuthSession;
import io.github.addxiaoyi.starx.common.auth.DeviceFingerprint;
import io.github.addxiaoyi.starx.common.auth.IpSessionStore;
import io.github.addxiaoyi.starx.common.auth.PremiumResolver;
import io.github.addxiaoyi.starx.common.auth.SessionManager;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthBridge;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcTrustedDeviceRepository;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.model.StarxUser;
import io.github.addxiaoyi.starx.uworld.UworldEnterResult;
import io.github.addxiaoyi.starx.uworld.UworldFlowHandler;
import io.github.addxiaoyi.starx.uworld.UworldFlowOptions;
import io.github.addxiaoyi.starx.uworld.UworldFlowSession;
import io.github.addxiaoyi.starx.uworld.UworldHandle;
import io.github.addxiaoyi.starx.uworld.UworldOutcome;
import io.github.addxiaoyi.starx.uworld.UworldOutcomeType;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.velocity.StarxVelocityPlugin;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.config.UworldConfig;
import io.github.addxiaoyi.starx.velocity.integration.TrustedIdentityProvider;
import io.github.addxiaoyi.starx.velocity.module.VelocityModule;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class AuthModule implements VelocityModule {

  private static final int MAX_LOGIN_ATTEMPTS = 5;
  private static final int MAX_TRACKED_LOGIN_IDENTITIES = 4_096;
  private static final Duration ATTEMPT_RESET = Duration.ofMinutes(1);
  private static final Component AUTH_ERROR = Component.text(
      "认证服务发生异常，请稍后重新连接。", NamedTextColor.RED);
  private static final Component AUTH_UNAVAILABLE = Component.text(
      "认证世界暂不可用，请稍后重新连接。", NamedTextColor.RED);
  private static final Component DUPLICATE_LOGIN = Component.text(
      "该账号已在其他位置连接。", NamedTextColor.RED);
  private static final Component RATE_LIMITED = Component.text(
      "登录尝试过于频繁，请稍后重新连接。", NamedTextColor.RED);
  private static final Component TARGET_UNAVAILABLE = Component.text(
      "认证完成，但目标服务器暂不可用。", NamedTextColor.RED);
  private static final Component BACKEND_BLOCKED = Component.text(
      "请先在认证世界完成登录。", NamedTextColor.RED);

  private final StarxVelocityPlugin plugin;
  private final EventBus eventBus;
  private final UniAuthConfig uniauthConfig;
  private final Logger logger;
  private final StarxConfig.TotpConfig totpConfig;
  private final StarxConfig.AuthUxConfig authUx;
  private final BindingVerificationService bindingVerification;
  private final JdbcTrustedDeviceRepository trustedDeviceRepository;
  private final IpSessionStore ipSessionStore;
  private final UworldConfig uworldConfig;
  private final AuthFlowIndex<Player, RegisteredServer, Component> flows =
      new AuthFlowIndex<>();
  private final LoginAttemptLimiter loginAttempts = new LoginAttemptLimiter(
      MAX_TRACKED_LOGIN_IDENTITIES, MAX_LOGIN_ATTEMPTS, ATTEMPT_RESET);

  private JdbcUserRepository userRepository;
  private SessionManager sessionManager;
  private PremiumResolver premiumResolver;
  private TrustedIdentityProvider trustedIdentity = TrustedIdentityProvider.none();
  private boolean trustedIdentityBound;
  private AuthService authService;
  private AuthCommandHandler commandHandler;
  private AuthCommands authCommands;
  private volatile UworldRuntime uworld;
  private volatile RegisteredServer targetServer;
  private UworldHandle authWorld;
  private EventListener listener;

  public AuthModule(
      StarxVelocityPlugin plugin,
      EventBus eventBus,
      UniAuthConfig uniauthConfig,
      StarxConfig.TotpConfig totpConfig,
      StarxConfig.AuthConfig authConfig,
      UworldConfig uworldConfig,
      JdbcUserRepository userRepository
  ) {
    this(plugin, eventBus, uniauthConfig, totpConfig, authConfig, uworldConfig,
        userRepository, new BindingVerificationService(), null, null);
  }

  public AuthModule(
      StarxVelocityPlugin plugin,
      EventBus eventBus,
      UniAuthConfig uniauthConfig,
      StarxConfig.TotpConfig totpConfig,
      StarxConfig.AuthConfig authConfig,
      UworldConfig uworldConfig,
      JdbcUserRepository userRepository,
      BindingVerificationService bindingVerification
  ) {
    this(plugin, eventBus, uniauthConfig, totpConfig, authConfig, uworldConfig,
        userRepository, bindingVerification, null, null);
  }

  public AuthModule(
      StarxVelocityPlugin plugin,
      EventBus eventBus,
      UniAuthConfig uniauthConfig,
      StarxConfig.TotpConfig totpConfig,
      StarxConfig.AuthConfig authConfig,
      UworldConfig uworldConfig,
      JdbcUserRepository userRepository,
      BindingVerificationService bindingVerification,
      JdbcTrustedDeviceRepository trustedDeviceRepository
  ) {
    this(plugin, eventBus, uniauthConfig, totpConfig, authConfig, uworldConfig,
        userRepository, bindingVerification, trustedDeviceRepository, null);
  }

  public AuthModule(
      StarxVelocityPlugin plugin,
      EventBus eventBus,
      UniAuthConfig uniauthConfig,
      StarxConfig.TotpConfig totpConfig,
      StarxConfig.AuthConfig authConfig,
      UworldConfig uworldConfig,
      JdbcUserRepository userRepository,
      BindingVerificationService bindingVerification,
      JdbcTrustedDeviceRepository trustedDeviceRepository,
      IpSessionStore ipSessionStore
  ) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
    this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    this.uniauthConfig = Objects.requireNonNull(uniauthConfig, "uniauthConfig");
    this.totpConfig = Objects.requireNonNull(totpConfig, "totpConfig");
    this.uworldConfig = Objects.requireNonNull(uworldConfig, "uworldConfig");
    this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
    this.bindingVerification = Objects.requireNonNull(bindingVerification, "bindingVerification");
    this.trustedDeviceRepository = trustedDeviceRepository;
    this.ipSessionStore = ipSessionStore;
    this.logger = plugin.logger();
    StarxConfig.AuthConfig resolvedAuth = Objects.requireNonNull(authConfig, "authConfig");
    this.authUx = resolvedAuth.ux();
    this.initAuthService(resolvedAuth);
  }

  public void bindUworldRuntime(UworldRuntime runtime) {
    if (this.uworld != null) {
      throw new IllegalStateException("Uworld runtime is already bound to authentication");
    }
    this.uworld = Objects.requireNonNull(runtime, "runtime");
  }

  public void bindTrustedIdentityProvider(TrustedIdentityProvider provider) {
    if (this.trustedIdentityBound) {
      throw new IllegalStateException("Trusted identity provider is already bound");
    }
    this.trustedIdentity = Objects.requireNonNull(provider, "provider");
    this.trustedIdentityBound = true;
  }

  public JdbcUserRepository userRepository() {
    return this.userRepository;
  }

  public AuthService authService() {
    return this.authService;
  }

  public boolean requiresAuth(Player player) {
    return this.flows.requiresAuth(player);
  }

  public boolean approveWebLogin(UUID playerId, AuthLease lease) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(lease, "lease");
    if (this.authService.isAuthenticated(lease, playerId)) return true;
    Player player = this.plugin.proxy().getPlayer(playerId).orElse(null);
    if (player == null || this.flows.lease(player).filter(lease::equals).isEmpty()) {
      return false;
    }
    UworldFlowSession flowSession = this.uworld.session(player).orElse(null);
    if (flowSession == null) return false;
    CompletableFuture<Boolean> completion = new CompletableFuture<>();
    try {
      flowSession.execute(() -> {
        try {
          if (!this.isCurrentFlow(player, flowSession, lease)) {
            completion.complete(false);
            return;
          }
          AuthResult result = this.authService.approveWebLogin(lease, playerId);
          if (!result.success()) {
            completion.complete(false);
            return;
          }
          player.sendMessage(Component.text(
              "网页登录确认成功，正在进入服务器。", NamedTextColor.GREEN));
          this.routeAuthenticatedPlayer(player);
          completion.complete(true);
        } catch (RuntimeException error) {
          completion.completeExceptionally(error);
        }
      });
      return completion.get(5, TimeUnit.SECONDS);
    } catch (Exception error) {
      this.logger.log(Level.WARNING,
          "Unable to complete web login approval for " + playerId, error);
      return false;
    }
  }

  @Override
  public String name() {
    return "starx.auth";
  }

  static boolean hasReadyUworld(UworldRuntime runtime) {
    return runtime != null && runtime.isReady();
  }
  @Override
  public void onEnable() {
    try {
      UworldRuntime runtime = this.uworld;
      this.targetServer = this.plugin.proxy()
          .getServer(this.uworldConfig.auth().targetServer())
          .orElseThrow(() -> new IllegalStateException(
              "Authenticated target server is not registered: "
                  + this.uworldConfig.auth().targetServer()));

      if (hasReadyUworld(runtime)) {
        AuthUworldDefinition definition = new AuthUworldDefinition(
            this.plugin.dataDirectory(),
            this.uworldConfig.auth(),
            this.logger::warning,
            this.logger::info);
        UworldRuntime readyRuntime = Objects.requireNonNull(runtime);
        this.authWorld = readyRuntime.createWorld(
            "starx.auth", definition.spec(), definition.generator());
        this.logger.info("Authentication Uworld ready");
      } else {
        this.authWorld = null;
        this.logger.warning(
            "Authentication Uworld is unavailable; trusted identity auto-login remains enabled, "
                + "while password authentication is unavailable");
      }

      this.listener = new EventListener();
      this.plugin.proxy().getEventManager().register(this.plugin, this.listener);
      this.authCommands = new AuthCommands(this.plugin, this.authService);
      this.authCommands.onEnable();
    } catch (RuntimeException error) {
      this.rollbackEnable(error);
      throw new IllegalStateException("Unable to start authentication", error);
    }
  }

  @Override
  public void onDisable() {
    IllegalStateException failure = null;
    SessionManager currentSessions = this.sessionManager;
    this.sessionManager = null;
    EventListener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      try {
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
      } catch (RuntimeException error) {
        failure = addStopFailure(failure, "authentication listener", error);
      }
    }
    AuthCommands currentCommands = this.authCommands;
    this.authCommands = null;
    if (currentCommands != null) {
      try {
        currentCommands.onDisable();
      } catch (RuntimeException error) {
        failure = addStopFailure(failure, "authentication commands", error);
      }
    }
    UworldHandle currentWorld = this.authWorld;
    this.authWorld = null;
    if (currentWorld != null) {
      try {
        currentWorld.closeAsync(Component.text("认证服务正在关闭"))
            .toCompletableFuture()
            .join();
      } catch (RuntimeException error) {
        failure = addStopFailure(failure, "authentication Uworld", error);
      }
    }
    this.targetServer = null;
    this.flows.clear();
    this.loginAttempts.clear();
    if (currentSessions != null) {
      failure = stopAuthenticationSessions(failure, currentSessions::shutdown);
    }
    if (failure != null) {
      throw failure;
    }
  }

  static IllegalStateException stopAuthenticationSessions(
      IllegalStateException failure,
      Runnable shutdown
  ) {
    Objects.requireNonNull(shutdown, "shutdown");
    try {
      shutdown.run();
      return failure;
    } catch (RuntimeException error) {
      return addStopFailure(failure, "authentication sessions", error);
    }
  }

  private static IllegalStateException addStopFailure(
      IllegalStateException failure,
      String resource,
      RuntimeException error
  ) {
    IllegalStateException aggregate = failure == null
        ? new IllegalStateException("One or more authentication resources failed to stop")
        : failure;
    aggregate.addSuppressed(new IllegalStateException(
        "Unable to stop " + resource, error));
    return aggregate;
  }

  private void rollbackEnable(RuntimeException failure) {
    EventListener currentListener = this.listener;
    this.listener = null;
    if (currentListener != null) {
      try {
        this.plugin.proxy().getEventManager().unregisterListener(this.plugin, currentListener);
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
    }
    AuthCommands currentCommands = this.authCommands;
    this.authCommands = null;
    if (currentCommands != null) {
      try {
        currentCommands.onDisable();
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
    }
    UworldHandle currentWorld = this.authWorld;
    this.authWorld = null;
    if (currentWorld != null) {
      try {
        currentWorld.closeAsync(Component.text("认证服务启动失败"))
            .toCompletableFuture()
            .join();
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
    }
    this.targetServer = null;
  }

  private void initAuthService(StarxConfig.AuthConfig authConfig) {
    this.sessionManager = new SessionManager(Duration.ofMinutes(10), Instant::now);
    this.premiumResolver = new PremiumResolver();

    UniAuthBridge uniAuthBridge = null;
    if (this.uniauthConfig.enabled() && this.uniauthConfig.bridgeMode()) {
      UniAuthClient uniAuthClient = new UniAuthClient(this.uniauthConfig);
      uniAuthBridge = new UniAuthBridge(
          this.uniauthConfig, uniAuthClient, this.userRepository);
    }
    this.authService = new AuthService(this.userRepository, this.eventBus, this.sessionManager,
        this.uniauthConfig, uniAuthBridge,
        this.ipSessionStore, this.trustedDeviceRepository);
    this.authService.setIpBypassMinutes(authConfig.passwordBypassMinutes());
    this.commandHandler = new AuthCommandHandler(this.authService);
  }

  private Optional<Component> beginLogin(Player player, AuthLease lease) {
    UUID playerId = player.getUniqueId();
    String username = player.getUsername();
    InetAddress address = this.playerAddress(player);
    boolean premium = this.premiumResolver.isPremium(playerId, player.isOnlineMode());
    boolean trustedExternalIdentity = this.trustedIdentity.isTrusted(playerId);
    boolean trustedWebsiteBinding = this.userRepository.hasTrustedWebsiteBinding(playerId, username);
    boolean recentPasswordLogin = address != null
        && this.authService.shouldBypassAuth(
            playerId, address.getHostAddress(), this.deviceId(player), false, false, false);
    if (AuthAdmissionPolicy.canAutoLogin(premium, trustedExternalIdentity || trustedWebsiteBinding) || recentPasswordLogin) {
      AuthResult result = premium
          ? this.authService.autoLogin(lease, playerId, username, address)
          : recentPasswordLogin
          ? this.authService.autoLoginTrusted(lease, playerId, username, address, "ip-session", false)
          : this.authService.autoLoginTrusted(
              lease,
              playerId,
              username,
              address,
              trustedWebsiteBinding ? "website-binding" : "floodgate",
              false);
      if (!result.success()) {
        return Optional.of(Component.text(result.message()));
      }
      RegisteredServer target = this.targetServer;
      if (target == null) {
        this.logMissingTarget();
        return Optional.of(TARGET_UNAVAILABLE);
      }
      if (!this.flows.route(player, target)) {
        return Optional.of(AUTH_ERROR);
      }
      return Optional.empty();
    }

    if (!this.checkRateLimit(playerId)) {
      return Optional.of(RATE_LIMITED);
    }
    if (this.uworld == null || !this.uworld.isReady() || this.authWorld == null) {
      return Optional.of(AUTH_UNAVAILABLE);
    }
    if (!this.flows.awaitPassword(player)) {
      return Optional.of(AUTH_ERROR);
    }
    this.eventBus.publish("player:login:start", Map.of("uuid", playerId, "username", username));
    this.logger.log(Level.INFO, "Player {0} is waiting for Uworld authentication", username);
    return Optional.empty();
  }

  private void enterAuthWorld(Player player) {
    if (this.flows.phase(player).orElse(null) == AuthFlowIndex.Phase.DENIED) {
      player.disconnect(this.flows.denial(player).orElse(AUTH_ERROR));
      return;
    }
    UworldHandle world = this.authWorld;
    if (world == null || !this.flows.requiresInput(player)) {
      return;
    }
    UworldFlowOptions options = new UworldFlowOptions(
        Duration.ofSeconds(this.uworldConfig.auth().timeoutSeconds()),
        Duration.ofSeconds(this.uworldConfig.transferTimeoutSeconds()));
    UworldEnterResult result = world.enter(player, options, new AuthFlowHandler(player));
    if (result instanceof UworldEnterResult.Rejected rejected) {
      this.logger.log(Level.SEVERE, "Unable to enter auth Uworld for {0}: {1}",
          new Object[]{player.getUsername(), rejected.status()});
      this.flows.deny(player, AUTH_UNAVAILABLE);
      player.disconnect(AUTH_UNAVAILABLE);
    }
  }

  private void handleAuthInput(
      UworldFlowSession flowSession,
      Player player,
      String message
  ) {
    AuthLease lease = this.flows.lease(player).orElse(null);
    if (lease == null) {
      return;
    }
    AuthFlowIndex.InputType input = this.flows.claimInput(player).orElse(null);
    if (input == null) {
      return;
    }

    UUID playerId = player.getUniqueId();
    String username = player.getUsername();
    InetAddress address = this.playerAddress(player);
    String deviceId = this.deviceId(player);
    try {
      this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
        AuthResult result;
        try {
          result = input == AuthFlowIndex.InputType.PASSWORD
              ? this.commandHandler.handleCredentials(
                  lease, playerId, username, message, address, deviceId)
              : this.commandHandler.handleSecondFactor(lease, playerId, message);
        } catch (RuntimeException error) {
          this.returnAuthFailure(flowSession, player, lease, input, error);
          return;
        }
        this.returnAuthResult(flowSession, player, lease, input, result);
      }).schedule();
    } catch (RuntimeException error) {
      this.flows.retryInput(player, input);
      this.logger.log(Level.SEVERE,
          "Unable to schedule authentication for " + username, error);
      flowSession.fail(AUTH_ERROR);
    }
  }

  private void returnAuthResult(
      UworldFlowSession flowSession,
      Player player,
      AuthLease lease,
      AuthFlowIndex.InputType input,
      AuthResult result
  ) {
    try {
      flowSession.execute(() -> {
        if (!this.isCurrentFlow(player, flowSession, lease)) {
          this.authService.cancelAuthentication(player.getUniqueId(), lease);
          return;
        }
        this.applyAuthResult(player, input, result);
      });
    } catch (RuntimeException error) {
      this.logger.log(Level.SEVERE,
          "Unable to return authentication result for " + player.getUsername(), error);
      this.authService.cancelAuthentication(player.getUniqueId(), lease);
    }
  }

  private void returnAuthFailure(
      UworldFlowSession flowSession,
      Player player,
      AuthLease lease,
      AuthFlowIndex.InputType input,
      RuntimeException error
  ) {
    this.logger.log(Level.SEVERE,
        "Authentication verification failed for " + player.getUsername(), error);
    try {
      flowSession.execute(() -> {
        if (!this.isCurrentFlow(player, flowSession, lease)) {
          this.authService.cancelAuthentication(player.getUniqueId(), lease);
          return;
        }
        this.flows.retryInput(player, input);
        this.flows.deny(player, AUTH_ERROR);
        this.authService.cancelAuthentication(player.getUniqueId(), lease);
        flowSession.fail(AUTH_ERROR);
      });
    } catch (RuntimeException dispatchError) {
      this.logger.log(Level.SEVERE,
          "Unable to return authentication failure for " + player.getUsername(),
          dispatchError);
      this.authService.cancelAuthentication(player.getUniqueId(), lease);
    }
  }

  private boolean isCurrentFlow(
      Player player,
      UworldFlowSession expected,
      AuthLease lease
  ) {
    if (!this.flows.requiresAuth(player)) {
      return false;
    }
    if (this.flows.lease(player).filter(lease::equals).isEmpty()) {
      return false;
    }
    return this.uworld.session(player).filter(current -> current == expected).isPresent();
  }

  private void applyAuthResult(
      Player player,
      AuthFlowIndex.InputType input,
      AuthResult result
  ) {
    if (!result.success()) {
      this.flows.retryInput(player, input);
      player.sendMessage(Component.text(AuthUxText.playerMessage(result), NamedTextColor.RED));
      this.playFeedback(player, this.authUx.errorSound(), 0.45f, 0.7f);
      return;
    }

    if (result.state() == AuthSession.State.AUTHENTICATING) {
      if (!this.totpConfig.enabled() || !this.flows.awaitTotp(player)) {
        Component notice = Component.text("二步验证当前不可用，请联系管理员。", NamedTextColor.RED);
        this.flows.deny(player, notice);
        player.disconnect(notice);
        return;
      }
      this.showTotpPrompt(player);
      return;
    }

    if (result.state() == AuthSession.State.WEB_APPROVAL_PENDING) {
      if (result.webApprovalUrl() == null || !this.flows.awaitWebApproval(player)) {
        Component notice = Component.text("网页登录确认当前不可用，请重新登录。", NamedTextColor.RED);
        this.flows.deny(player, notice);
        player.disconnect(notice);
        return;
      }
      Component link = Component.text("点击这里确认本次登录", NamedTextColor.AQUA)
          .clickEvent(ClickEvent.openUrl(result.webApprovalUrl()))
          .hoverEvent(HoverEvent.showText(Component.text("打开 StarMC 账号安全页面")));
      player.sendMessage(Component.text("检测到新的登录环境。", NamedTextColor.YELLOW));
      player.sendMessage(link);
      this.playFeedback(player, this.authUx.promptSound(), 0.45f, 1.1f);
      return;
    }

    player.sendMessage(Component.text(AuthUxText.playerMessage(result), NamedTextColor.GREEN));
    if (this.authUx.titlesEnabled()) {
      player.showTitle(Title.title(
          Component.text(this.authUx.messages().successTitle(), NamedTextColor.GREEN),
          Component.text(this.authUx.messages().successSubtitle(), NamedTextColor.GRAY)));
    }
    this.playFeedback(player, this.authUx.successSound(), 0.55f, 1.3f);
    this.routeAuthenticatedPlayer(player);
  }

  private void routeAuthenticatedPlayer(Player player) {
    RegisteredServer target = this.targetServer;
    if (target == null) {
      this.logMissingTarget();
      this.flows.deny(player, TARGET_UNAVAILABLE);
      this.uworld.session(player).ifPresent(session -> session.fail(TARGET_UNAVAILABLE));
      player.disconnect(TARGET_UNAVAILABLE);
      return;
    }
    if (!this.flows.route(player, target)) {
      this.flows.deny(player, AUTH_ERROR);
      player.disconnect(AUTH_ERROR);
      return;
    }

    UworldFlowSession session = this.uworld.session(player).orElse(null);
    if (session == null || !session.complete(target)) {
      this.flows.deny(player, TARGET_UNAVAILABLE);
      player.disconnect(TARGET_UNAVAILABLE);
    }
  }

  private boolean checkRateLimit(UUID playerId) {
    return this.loginAttempts.allow(playerId, Instant.now());
  }

  private void scheduleAuthPrompt(UworldFlowSession flowSession, Player player) {
    AuthLease lease = this.flows.lease(player).orElse(null);
    if (lease == null) {
      return;
    }
    try {
      this.plugin.proxy().getScheduler().buildTask(this.plugin, () -> {
        boolean registered;
        StarxUser user;
        try {
          registered = this.authService.isUserRegistered(player.getUniqueId());
          user = this.userRepository.findFullByUuid(player.getUniqueId()).orElse(null);
        } catch (RuntimeException error) {
          this.returnPromptFailure(flowSession, player, lease, error);
          return;
        }
        try {
          flowSession.execute(() -> {
            if (this.isCurrentFlow(player, flowSession, lease)) {
              this.showAuthPrompt(player, registered, user);
            }
          });
        } catch (RuntimeException error) {
          this.logger.log(Level.SEVERE,
              "Unable to return authentication prompt for " + player.getUsername(), error);
          this.authService.cancelAuthentication(player.getUniqueId(), lease);
        }
      }).schedule();
    } catch (RuntimeException error) {
      this.flows.deny(player, AUTH_ERROR);
      this.logger.log(Level.SEVERE,
          "Unable to schedule authentication prompt for " + player.getUsername(), error);
      flowSession.fail(AUTH_ERROR);
    }
  }

  private void returnPromptFailure(
      UworldFlowSession flowSession,
      Player player,
      AuthLease lease,
      RuntimeException error
  ) {
    this.logger.log(Level.SEVERE,
        "Unable to load authentication prompt for " + player.getUsername(), error);
    try {
      flowSession.execute(() -> {
        if (!this.isCurrentFlow(player, flowSession, lease)) {
          this.authService.cancelAuthentication(player.getUniqueId(), lease);
          return;
        }
        this.flows.deny(player, AUTH_ERROR);
        flowSession.fail(AUTH_ERROR);
      });
    } catch (RuntimeException dispatchError) {
      this.logger.log(Level.SEVERE,
          "Unable to return authentication prompt failure for " + player.getUsername(),
          dispatchError);
      this.authService.cancelAuthentication(player.getUniqueId(), lease);
    }
  }

  private void showAuthPrompt(Player player, boolean registered, StarxUser user) {
    if (!this.flows.requiresInput(player)) {
      return;
    }
    StarxConfig.AuthUxMessages messages = this.authUx.messages();
    StarxConfig.AuthCardMessages card = this.authUx.card();
    InetAddress address = this.playerAddress(player);
    String currentIp = address == null ? card.unknownValue() : address.getHostAddress();
    RegisteredServer target = this.targetServer;
    String targetName = target == null
        ? card.targetUnavailable()
        : target.getServerInfo().getName();
    String accountCenterUrl = this.plugin.config().auth().bindingWebsiteUrl()
        + "/profile?tab=minecraft&username="
        + java.net.URLEncoder.encode(
            player.getUsername(), java.nio.charset.StandardCharsets.UTF_8)
        + "&uuid=" + player.getUniqueId();
    if (registered) {
      String code = this.bindingVerification.generateCode(player.getUniqueId());
      String bindingUrl = accountCenterUrl + "&code=" + code;
      player.sendMessage(AuthLoginCard.render(
          user,
          currentIp,
          targetName,
          bindingUrl,
          card));
      player.sendMessage(Component.text(messages.loginPrompt(), NamedTextColor.YELLOW));
      if (this.authUx.actionBarEnabled()) {
        player.sendActionBar(Component.text(messages.loginActionBar(), NamedTextColor.GRAY));
      }
      if (this.authUx.titlesEnabled()) {
        player.showTitle(Title.title(
            Component.text(messages.loginTitle(), NamedTextColor.GOLD),
            Component.text(messages.loginSubtitle(), NamedTextColor.GRAY)));
      }
      this.playFeedback(player, this.authUx.promptSound(), 0.55f, 1.15f);
      return;
    }
    if (this.authUx.titlesEnabled()) {
      player.showTitle(Title.title(
          Component.text(messages.registerTitle(), NamedTextColor.AQUA),
          Component.text(messages.registerSubtitle(), NamedTextColor.GRAY)));
    }
    player.sendMessage(AuthLoginCard.renderRegistration(
        player.getUsername(),
        player.getUniqueId(),
        player.isOnlineMode(),
        currentIp,
        targetName,
        accountCenterUrl,
        card));
    player.sendMessage(Component.text(messages.registerPrompt(), NamedTextColor.YELLOW));
    if (this.authUx.actionBarEnabled()) {
      player.sendActionBar(Component.text(messages.registerActionBar(), NamedTextColor.GRAY));
    }
    this.playFeedback(player, this.authUx.promptSound(), 0.55f, 1.15f);
  }

  private void showTotpPrompt(Player player) {
    StarxConfig.AuthUxMessages messages = this.authUx.messages();
    if (this.authUx.titlesEnabled()) {
      player.showTitle(Title.title(
          Component.text(messages.totpTitle(), NamedTextColor.GOLD),
          Component.text(messages.totpSubtitle(), NamedTextColor.WHITE)));
    }
    player.sendMessage(Component.text(messages.totpPrompt(), NamedTextColor.YELLOW));
    if (this.authUx.actionBarEnabled()) {
      player.sendActionBar(Component.text(messages.totpActionBar(), NamedTextColor.GRAY));
    }
    this.playFeedback(player, this.authUx.promptSound(), 0.55f, 1.15f);
  }

  private void playFeedback(Player player, String soundKey, float volume, float pitch) {
    if (!this.authUx.soundsEnabled()) {
      return;
    }
    try {
      player.playSound(Sound.sound(
          Key.key(soundKey), Sound.Source.MASTER, volume, pitch));
    } catch (RuntimeException error) {
      this.logger.log(Level.FINE,
          "Unable to send authentication feedback sound to " + player.getUsername(), error);
    }
  }

  private InetAddress playerAddress(Player player) {
    InetSocketAddress address = player.getRemoteAddress();
    return address == null ? null : address.getAddress();
  }

  private String deviceId(Player player) {
    InetAddress address = this.playerAddress(player);
    if (address == null) {
      return null;
    }
    String virtualHost = player.getVirtualHost()
        .map(InetSocketAddress::getHostString)
        .orElse("unknown");
    return DeviceFingerprint.from(
        address, player.getProtocolVersion().getProtocol(), player.isOnlineMode(), virtualHost);
  }

  private void logMissingTarget() {
    this.logger.log(Level.SEVERE, "Authenticated target server is not registered: {0}",
        this.uworldConfig.auth().targetServer());
  }

  private final class AuthFlowHandler implements UworldFlowHandler {
    private final Player player;
    private final VoidRescueState voidRescue;

    private AuthFlowHandler(Player player) {
      this.player = player;
      double threshold = AuthModule.this.uworldConfig.auth().world().spawnY()
          - AuthModule.this.uworldConfig.auth().world().voidRescueThreshold();
      this.voidRescue = new VoidRescueState(threshold);
    }

    @Override
    public void onReady(UworldFlowSession session) {
      AuthModule.this.scheduleAuthPrompt(session, this.player);
    }

    @Override
    public void onChat(UworldFlowSession session, String message) {
      AuthModule.this.handleAuthInput(session, this.player, message);
    }

    @Override
    public void onMove(UworldFlowSession session, double x, double y, double z) {
      this.voidRescue.observePosition(y);
      if (!this.voidRescue.shouldRescue(y)) {
        return;
      }
      if (!session.teleportToSpawn()) {
        this.voidRescue.cancelPending();
      }
    }

    @Override
    public void onOutcome(UworldFlowSession session, UworldOutcome outcome) {
      if (outcome.type() == UworldOutcomeType.TRANSFERRED) {
        return;
      }
      if (AuthModule.this.flows.deny(this.player)) {
        AuthModule.this.flows.lease(this.player).ifPresent(lease ->
            AuthModule.this.authService.cancelAuthentication(
                this.player.getUniqueId(), lease));
      }
    }
  }

  private final class EventListener {

    @Subscribe(order = PostOrder.FIRST)
    public EventTask onLogin(LoginEvent event) {
      Player player = event.getPlayer();
      UUID playerId = player.getUniqueId();
      if (AuthModule.this.flows.begin(playerId, player, DUPLICATE_LOGIN)
          == AuthFlowIndex.BeginResult.DUPLICATE) {
        event.setResult(ResultedEvent.ComponentResult.denied(DUPLICATE_LOGIN));
        return null;
      }
      AuthLease lease = AuthModule.this.flows.lease(player).orElseThrow();
      if (!AuthModule.this.authService.openConnection(
          lease,
          playerId,
          player.getUsername(),
          AuthModule.this.playerAddress(player),
          AuthModule.this.deviceId(player))) {
        AuthModule.this.flows.deny(player, AUTH_UNAVAILABLE);
        event.setResult(ResultedEvent.ComponentResult.denied(AUTH_UNAVAILABLE));
        return null;
      }
      return EventTask.async(() -> {
        try {
          AuthModule.this.beginLogin(player, lease).ifPresent(reason -> {
            AuthModule.this.flows.deny(player, reason);
            event.setResult(ResultedEvent.ComponentResult.denied(reason));
          });
        } catch (RuntimeException error) {
          AuthModule.this.logger.log(Level.SEVERE,
              "Authentication admission failed for " + player.getUsername(), error);
          AuthModule.this.flows.deny(player, AUTH_ERROR);
          event.setResult(ResultedEvent.ComponentResult.denied(AUTH_ERROR));
        }
      });
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLoginFinal(LoginEvent event) {
      Player player = event.getPlayer();
      AuthLoginBarrier.enforceAndClose(
          AuthModule.this.flows,
          player,
          player.getUniqueId(),
          event.getResult().isAllowed(),
          event.getResult().getReasonComponent(),
          AUTH_ERROR,
          AuthModule.this.authService).ifPresent(reason ->
              event.setResult(ResultedEvent.ComponentResult.denied(reason)));
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
      AuthModule.this.enterAuthWorld(event.getPlayer());
    }

    @Subscribe(order = PostOrder.LAST)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
      AuthModule.this.flows.target(event.getPlayer()).ifPresent(event::setInitialServer);
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
      Player player = event.getPlayer();
      if (!AuthModule.this.flows.requiresAuth(player)) {
        return;
      }
      RegisteredServer target = event.getResult().getServer().orElse(null);
      if (AuthModule.this.flows.allowsBackend(player, target)) {
        return;
      }
      event.setResult(ServerPreConnectEvent.ServerResult.denied());
      if (AuthModule.this.flows.phase(player).orElse(null)
          == AuthFlowIndex.Phase.TARGET_PENDING) {
        AuthModule.this.flows.deny(player, BACKEND_BLOCKED);
        player.disconnect(BACKEND_BLOCKED);
      }
    }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
      Player player = event.getPlayer();
      if (!AuthModule.this.flows.requiresAuth(player)) {
        return;
      }
      event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
      Player player = event.getPlayer();
      UUID playerId = player.getUniqueId();
      AuthLease lease = AuthModule.this.flows.lease(player).orElse(null);
      if (AuthModule.this.flows.close(playerId, player) && lease != null) {
        AuthModule.this.authService.closeConnection(playerId, lease);
      }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
      Player player = event.getPlayer();
      AuthFlowIndex.ConnectResult result = AuthModule.this.flows.connected(
          player, event.getServer());
      if (result == AuthFlowIndex.ConnectResult.WRONG_TARGET) {
        player.disconnect(BACKEND_BLOCKED);
      } else if (result == AuthFlowIndex.ConnectResult.COMPLETED) {
        AuthModule.this.loginAttempts.remove(player.getUniqueId());
      } else if (result == AuthFlowIndex.ConnectResult.IGNORED
          && AuthModule.this.flows.requiresAuth(player)) {
        AuthModule.this.logger.log(Level.SEVERE,
            "Pending player {0} bypassed the authentication route barrier",
            player.getUsername());
        AuthModule.this.flows.deny(player, BACKEND_BLOCKED);
        player.disconnect(BACKEND_BLOCKED);
      }
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
      Player player = event.getPlayer();
      Component reason = event.getServerKickReason().orElse(TARGET_UNAVAILABLE);
      if (AuthModule.this.flows.deny(player, reason)) {
        AuthModule.this.flows.lease(player).ifPresent(lease ->
            AuthModule.this.authService.cancelAuthentication(
                player.getUniqueId(), lease));
        player.disconnect(reason);
      }
    }
  }

}
