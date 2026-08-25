/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.inject.Inject
 *  com.velocitypowered.api.event.Subscribe
 *  com.velocitypowered.api.event.proxy.ProxyInitializeEvent
 *  com.velocitypowered.api.event.proxy.ProxyShutdownEvent
 *  com.velocitypowered.api.plugin.annotation.DataDirectory
 *  com.velocitypowered.api.proxy.ProxyServer
 */
package io.github.addxiaoyi.starx.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.addxiaoyi.starx.api.bridge.PlatformKind;
import io.github.addxiaoyi.starx.api.dto.UserDto;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.api.event.StarxEvent;
import io.github.addxiaoyi.starx.api.compat.CompatibilityReport;
import io.github.addxiaoyi.starx.api.extension.StarxCapabilities;
import io.github.addxiaoyi.starx.api.extension.StarxService;
import io.github.addxiaoyi.starx.api.extension.StarxServiceProvider;
import io.github.addxiaoyi.starx.runtime.extension.DefaultStarxService;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import io.github.addxiaoyi.starx.common.binding.JdbcBindingChallengeRepository;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthClient;
import io.github.addxiaoyi.starx.common.auth.uniauth.UniAuthConfig;
import io.github.addxiaoyi.starx.common.database.JdbcAnnouncementRepository;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcPunishmentRepository;
import io.github.addxiaoyi.starx.common.database.JdbcReportRepository;
import io.github.addxiaoyi.starx.common.database.JdbcStaffNoteRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcTrustedDeviceRepository;
import io.github.addxiaoyi.starx.common.database.JdbcRuntimeSettingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcTutorialProgressRepository;
import io.github.addxiaoyi.starx.common.platform.MaintenanceStateService;
import io.github.addxiaoyi.starx.velocity.routing.BackendRoutingService;
import io.github.addxiaoyi.starx.common.database.JdbcVoteRepository;
import io.github.addxiaoyi.starx.common.identity.AccountIdentityResolver;
import io.github.addxiaoyi.starx.common.identity.JdbcAccountIdentityRepository;
import io.github.addxiaoyi.starx.uworld.UworldRuntime;
import io.github.addxiaoyi.starx.velocity.bridge.VelocityBackendBridge;
import io.github.addxiaoyi.starx.velocity.config.ConfigLoader;
import io.github.addxiaoyi.starx.velocity.config.UpdateConfig;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.config.VelocityAutoConfigurator;
import io.github.addxiaoyi.starx.velocity.database.DatabaseManager;
import io.github.addxiaoyi.starx.velocity.event.VelocityEventBus;
import io.github.addxiaoyi.starx.velocity.operations.IncidentTimeline;
import io.github.addxiaoyi.starx.velocity.http.HttpApiServer;
import io.github.addxiaoyi.starx.velocity.http.WebhookClient;
import io.github.addxiaoyi.starx.velocity.http.FileWebhookOutbox;
import io.github.addxiaoyi.starx.velocity.http.WebhookOutboxRecovery;
import io.github.addxiaoyi.starx.velocity.http.WebhookEventPublisher;
import io.github.addxiaoyi.starx.velocity.http.admin.CrossDeviceLoginApprovalGateway;
import io.github.addxiaoyi.starx.velocity.identity.OfflineIdentityPolicy;
import io.github.addxiaoyi.starx.velocity.integration.FloodgateIntegrationModule;
import io.github.addxiaoyi.starx.velocity.integration.LuckPermsContextModule;
import io.github.addxiaoyi.starx.velocity.integration.TabIntegrationModule;
import io.github.addxiaoyi.starx.velocity.messaging.VelocityMessageBridge;
import io.github.addxiaoyi.starx.velocity.network.NetworkAutomationService;
import io.github.addxiaoyi.starx.velocity.network.RuntimePortConfiguration;
import io.github.addxiaoyi.starx.velocity.network.RuntimeEndpointRegistry;
import io.github.addxiaoyi.starx.velocity.network.TcpPortAllocator;
import io.github.addxiaoyi.starx.velocity.module.ModuleManager;
import io.github.addxiaoyi.starx.velocity.module.admin.AdminCommandsModule;
import io.github.addxiaoyi.starx.velocity.module.auth.AuthModule;
import io.github.addxiaoyi.starx.velocity.module.auth.CredentialChangeDisconnectService;
import io.github.addxiaoyi.starx.velocity.module.auth.ExternalHandshake;
import io.github.addxiaoyi.starx.velocity.module.auth.MigrationCommands;
import io.github.addxiaoyi.starx.velocity.module.auth.MigrationModule;
import io.github.addxiaoyi.starx.velocity.module.auth.UniAuthModule;
import io.github.addxiaoyi.starx.velocity.module.auth.YggdrasilModule;
import io.github.addxiaoyi.starx.velocity.module.integrations.MapModIntegrationModule;
import io.github.addxiaoyi.starx.velocity.module.integrations.PlanIntegrationModule;
import io.github.addxiaoyi.starx.velocity.module.integrations.QqIntegrationModule;
import io.github.addxiaoyi.starx.velocity.module.integrations.napcat.NapCatModule;
import io.github.addxiaoyi.starx.velocity.module.playerlist.PlayerListModule;
import io.github.addxiaoyi.starx.velocity.module.playerlist.PlayerListRenderer;
import io.github.addxiaoyi.starx.velocity.module.session.PlayerSessionModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.ChatModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.EnhancedProxyModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.FileCleanerModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.ForgeCompatModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.HubCommandModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.MaintenanceModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.MotdModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.OnlineSyncModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.ProxyInfoModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.QueueModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.TutorialModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.RakNetModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.ReconnectModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.RedirectModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.SmartQueueModule;
import io.github.addxiaoyi.starx.velocity.module.proxytools.queue.QueueService;
import io.github.addxiaoyi.starx.velocity.module.proxytools.smart.SmartQueueService;
import io.github.addxiaoyi.starx.velocity.module.security.AnticheatModule;
import io.github.addxiaoyi.starx.velocity.module.security.BlossomGuardModule;
import io.github.addxiaoyi.starx.velocity.module.security.BotFilterModule;
import io.github.addxiaoyi.starx.velocity.module.security.CrashFixModule;
import io.github.addxiaoyi.starx.velocity.module.security.RiskModule;
import io.github.addxiaoyi.starx.velocity.module.security.SmartAlertModule;
import io.github.addxiaoyi.starx.velocity.module.security.SmartRateLimitModule;
import io.github.addxiaoyi.starx.velocity.module.skin.SkinBridgeModule;
import io.github.addxiaoyi.starx.velocity.module.uworld.UworldDiagnostics;
import io.github.addxiaoyi.starx.velocity.module.uworld.UworldModule;
import io.github.addxiaoyi.starx.velocity.module.vote.VoteModule;
import io.github.addxiaoyi.starx.velocity.module.welcome.WelcomeModule;
import io.github.addxiaoyi.starx.velocity.security.HmacWebhookSigner;
import io.github.addxiaoyi.starx.velocity.website.VelocityWebsiteSync;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import io.github.addxiaoyi.starx.velocity.variable.StarxPlayerContextFactory;
import io.github.addxiaoyi.starx.velocity.variable.StarxVariableService;
import javax.sql.DataSource;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.common.account.JdbcAccountDeletionRepository;
import io.github.addxiaoyi.starx.common.account.AccountDeletionExecutor;
import io.github.addxiaoyi.starx.common.account.JdbcAccountErasureRepository;

public class StarxVelocityPlugin implements StarxServiceProvider {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginLifecycle lifecycle = new PluginLifecycle();
    private StarxConfig config;
    private DatabaseManager databaseManager;
    private VelocityEventBus eventBus;
    private HttpApiServer httpApiServer;
    private NetworkAutomationService networkAutomationService;
    private RuntimeEndpointRegistry runtimeEndpointRegistry;
    private WebhookClient webhookClient;
    private ModuleManager moduleManager;
    private UworldRuntime uworld;
    private DefaultStarxService extensionService;
    private Consumer<StarxEvent> extensionEventForwarder;
    private CompatibilityReport compatibilityReport;
    private volatile io.github.addxiaoyi.starx.common.update.UpdateManager updateManager;

    @Inject
    public StarxVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    public ProxyServer proxy() {
        return this.proxy;
    }

    public Logger logger() {
        return this.logger;
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }

    public StarxConfig config() {
        return this.config;
    }

    public CompatibilityReport compatibilityReport() {
        CompatibilityReport report = this.compatibilityReport;
        if (report == null) {
            throw new IllegalStateException("Compatibility report is not initialized");
        }
        return report;
    }

    public EventBus eventBus() {
        return this.eventBus;
    }

    public HttpApiServer httpApiServer() {
        return this.httpApiServer;
    }

    public WebhookClient webhookClient() {
        return this.webhookClient;
    }

    public ModuleManager moduleManager() {
        return this.moduleManager;
    }

    @Override
    public StarxService starxService() {
        DefaultStarxService service = this.extensionService;
        if (service == null) {
            throw new IllegalStateException("StarX extension service is not initialized");
        }
        return service;
    }

    public UworldRuntime uworld() {
        UworldRuntime runtime = this.uworld;
        if (runtime == null) {
            throw new IllegalStateException("Uworld runtime is not initialized");
        }
        return runtime;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws Exception {
        this.lifecycle.start(this::initialize);
    }

    private void initialize() throws Exception {
        this.logger.info("StarX Velocity \u521d\u59cb\u5316\u4e2d...");
        Path configFile = this.dataDirectory.resolve("config.yml");
        boolean firstBoot = Files.notExists(configFile);
        this.config = ConfigLoader.load(configFile, this.logger::warning);
        VelocityAutoConfigurator.Result autoConfig = VelocityAutoConfigurator.apply(
            configFile, this.proxy, firstBoot, this.logger::info);
        if (autoConfig.changed()) {
            this.config = ConfigLoader.load(configFile, this.logger::warning);
        }
        this.runtimeEndpointRegistry = RuntimeEndpointRegistry.open(this.dataDirectory);
        this.lifecycle.own(
            "runtime endpoint registry",
            this.runtimeEndpointRegistry::close);
        this.compatibilityReport = VelocityCompatibility.evaluate(
            this.proxy, configFile, this.dataDirectory, this.logger::info, this.logger::warning);
        this.lifecycle.own("compatibility report", () -> this.compatibilityReport = null);
        this.eventBus = new VelocityEventBus();
        this.lifecycle.own("event bus", this.eventBus::close);
        this.extensionService = new DefaultStarxService(
            this.implementationVersion(),
            PlatformKind.VELOCITY,
            Set.of(
                StarxCapabilities.AUTH,
                StarxCapabilities.UWORLD,
                StarxCapabilities.HTTP_API,
                StarxCapabilities.BACKEND_ROUTING));
        this.extensionEventForwarder = event -> {
            DefaultStarxService service = this.extensionService;
            if (service != null) {
                service.publishSystemEvent(event.type(), event.payload());
            }
        };
        this.eventBus.subscribeAll(this.extensionEventForwarder);
        this.lifecycle.own("extension service", () -> {
            Consumer<StarxEvent> forwarder = this.extensionEventForwarder;
            this.extensionEventForwarder = null;
            if (forwarder != null && this.eventBus != null) {
                this.eventBus.unsubscribeAll(forwarder);
            }
            DefaultStarxService service = this.extensionService;
            this.extensionService = null;
            if (service != null) {
                service.close();
            }
        });
        WebhookOutboxRecovery webhookOutbox = WebhookOutboxRecovery.open(
            this.dataDirectory.resolve("webhook-outbox.json"), System.currentTimeMillis());
        webhookOutbox.error().ifPresent(error -> this.logger.log(
            Level.WARNING,
            "StarX quarantined a corrupt webhook outbox at "
                + webhookOutbox.quarantine().orElseThrow(),
            error));
        this.webhookClient = new WebhookClient(
            this.config.webhook(),
            new HmacWebhookSigner(this.config.webhook().secret()),
            webhookOutbox.outbox());
        IncidentTimeline incidentTimeline = new IncidentTimeline(512, 256);
        this.eventBus.subscribeAll(incidentTimeline::append);
        this.databaseManager = new DatabaseManager(this.config.database());
        this.lifecycle.own("database", this.databaseManager::close);
        ExternalHandshake externalHandshake = ExternalHandshake.open(this.dataDirectory);
        this.lifecycle.own("Uworld reference", () -> this.uworld = null);
        DataSource defaultDataSource = this.databaseManager.commonManager().getDataSource();
        JdbcPlayerSessionRepository playerSessions = new JdbcPlayerSessionRepository(defaultDataSource);
        JdbcAccountDeletionRepository accountDeletions = new JdbcAccountDeletionRepository(defaultDataSource);
        JdbcAccountErasureRepository accountEraser = new JdbcAccountErasureRepository(defaultDataSource);
        java.util.concurrent.atomic.AtomicReference<AuthService> deletionAuth =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.function.Function<UUID, Set<UUID>>>
            deletionKnownMinecraftUuids = new java.util.concurrent.atomic.AtomicReference<>(uuid -> Set.of(uuid));
        AccountDeletionExecutor accountDeletionExecutor = new AccountDeletionExecutor(
            accountDeletions,
            (AccountDeletionExecutor.TransactionalEraser) (requestId, claimToken, playerUuid, erasedAt) -> {
                Set<UUID> knownUuids = deletionKnownMinecraftUuids.get().apply(playerUuid);
                accountEraser.eraseAndComplete(
                    accountDeletions, requestId, claimToken, playerUuid, erasedAt);
                AuthService auth = deletionAuth.get();
                Set<UUID> affectedUuids = new java.util.LinkedHashSet<>(knownUuids);
                affectedUuids.add(playerUuid);
                for (UUID sessionUuid : affectedUuids) {
                    if (sessionUuid == null) continue;
                    if (auth != null) {
                        auth.logout(sessionUuid);
                    }
                    this.proxy.getPlayer(sessionUuid).ifPresent(player -> player.disconnect(
                        net.kyori.adventure.text.Component.text(
                            "账号已删除，请重新连接。")));
                }
            });
        ScheduledTask accountDeletionTask = this.proxy.getScheduler()
            .buildTask(this, () -> {
                AccountDeletionExecutor.ExecutionSummary summary = accountDeletionExecutor.runOnce(System.currentTimeMillis());
                if (!summary.failedRequestIds().isEmpty()) {
                    this.logger.warning("StarX account deletion retry required: " + summary.failures());
                }
            })
            .repeat(Duration.ofMinutes(1))
            .schedule();
        this.lifecycle.own("account deletion executor", accountDeletionTask::cancel);
        JdbcUserRepository userRepository = new JdbcUserRepository(defaultDataSource);
        JdbcTrustedDeviceRepository trustedDeviceRepository =
            new JdbcTrustedDeviceRepository(defaultDataSource);
        io.github.addxiaoyi.starx.common.auth.JdbcIpSessionStore ipSessionStore =
            new io.github.addxiaoyi.starx.common.auth.JdbcIpSessionStore(
                new io.github.addxiaoyi.starx.common.database.JdbcIpSessionRepository(
                    defaultDataSource));
        JdbcBindingRepository bindingRepo = new JdbcBindingRepository(defaultDataSource);
        AccountIdentityResolver accountIdentities = new AccountIdentityResolver(
            new JdbcAccountIdentityRepository(defaultDataSource), userRepository);
        java.util.function.BiFunction<UUID, String, String> accountByPlayerWithName =
            accountIdentities::accountId;
        java.util.function.Function<UUID, String> accountByConnectedPlayer = playerId ->
            this.proxy.getPlayer(playerId)
                .map(player -> accountIdentities.accountId(playerId, player.getUsername()))
                .orElseGet(() -> accountIdentities.accountId(playerId));
        java.util.function.Function<UUID, java.util.Optional<UserDto>> identityAwareUserResolver =
            accountIdentities::resolveUser;
        java.util.function.Function<String, java.util.Optional<UserDto>> identityAwareUsernameResolver =
            accountIdentities::resolveUserByName;
        java.util.function.Function<String, java.util.Optional<io.github.addxiaoyi.starx.common.model.StarxUser>>
            identityAwareFullUsernameResolver = accountIdentities::resolveFullUserByName;
        java.util.function.Function<UUID, UUID> canonicalUuidResolver =
            accountIdentities::resolveMinecraftUuid;
        java.util.function.Function<UUID, java.util.Set<UUID>> knownMinecraftUuidsResolver =
            accountIdentities::knownMinecraftUuids;
        deletionKnownMinecraftUuids.set(knownMinecraftUuidsResolver);
        BindingChallengeService bindingChallenges =
            new BindingChallengeService(new JdbcBindingChallengeRepository(defaultDataSource));
        BindingVerificationService bindingVerification = new BindingVerificationService(
            bindingChallenges,
            accountByConnectedPlayer,
            accountIdentities::minecraftUuid,
            Clock.systemUTC(),
            Duration.ofMinutes(5));
        CrossDeviceApprovalService crossDeviceApprovals = new CrossDeviceApprovalService(
            bindingChallenges,
            accountByPlayerWithName,
            accountIdentities::minecraftUuid,
            accountIdentities::username);
        UniAuthClient uniAuthClient = null;
        UniAuthConfig uniAuthConfig = this.config.uniauth();
        if (uniAuthConfig != null && uniAuthConfig.enabled()) {
            uniAuthClient = new UniAuthClient(uniAuthConfig);
        }
        this.moduleManager = new ModuleManager(this.config);
        this.lifecycle.own("modules", this.moduleManager::disableAll);
        VelocityBackendBridge backendBridge = new VelocityBackendBridge(this);
        SkinBridgeModule skinBridge = new SkinBridgeModule(
            this, this.eventBus, backendBridge, knownMinecraftUuidsResolver);
        UworldModule uworldModule = new UworldModule(this, this.config.uworld());
        this.uworld = uworldModule;
        AuthModule authModule = new AuthModule(
            this,
            this.eventBus,
            this.config.uniauth(),
            this.config.totp(),
            this.config.auth(),
            this.config.uworld(),
            userRepository,
            bindingVerification,
            trustedDeviceRepository,
            ipSessionStore,
            externalHandshake
        );
        authModule.authService().bindMinecraftIdentityResolver(knownMinecraftUuidsResolver);
        authModule.authService().bindUsernameResolver(identityAwareFullUsernameResolver);
        authModule.authService().bindAccountErasure(
            playerUuid -> accountEraser.eraseAndCompletePending(
                accountDeletions,
                playerUuid,
                knownMinecraftUuidsResolver.apply(playerUuid),
                System.currentTimeMillis()));
        authModule.authService().bindMinecraftIdentityObserver(
            (uuid, username, trustedSource) ->
                accountIdentities.accountId(uuid, username, trustedSource));
        authModule.authService().bindMinecraftIdentityRollback(accountIdentities::remove);
        try {
            authModule.authService().bindWebLoginApprovalGateway(
                new CrossDeviceLoginApprovalGateway(
                    crossDeviceApprovals, this.config.auth().bindingWebsiteUrl()));
        } catch (IllegalArgumentException error) {
            this.logger.log(Level.WARNING,
                "Web login approval unavailable; TOTP fallback remains active", error);
        }
        CredentialChangeDisconnectService credentialDisconnects =
            new CredentialChangeDisconnectService(
                this.eventBus,
                task -> this.proxy.getScheduler().buildTask(this, task).schedule(),
                playerId -> this.proxy.getPlayer(playerId).ifPresent(player -> player.disconnect(
                    net.kyori.adventure.text.Component.text(
                        "账号凭据已更新，请重新连接并登录。"))));
        this.lifecycle.own("credential change disconnects", credentialDisconnects::close);
        deletionAuth.set(authModule.authService());
        PluginLifecycle.Ownership authFallback = this.lifecycle.own(
            "authentication sessions",
            authModule::onDisable
        );
        authModule.bindUworldRuntime(uworldModule);
        FloodgateIntegrationModule floodgate = new FloodgateIntegrationModule(this);
        authModule.bindTrustedIdentityProvider(floodgate);
        StarxConfig.OfflineIdentityConfig identityConfig = this.config.auth().offlineIdentity();
        OfflineIdentityPolicy offlineIdentity = new OfflineIdentityPolicy(identityConfig.prefix());
        StarxVariableService variables = new StarxVariableService(ZoneId.of("Asia/Shanghai"));
        StarxPlayerContextFactory playerContexts = new StarxPlayerContextFactory(
            offlineIdentity,
            identityConfig.displayName(),
            floodgate);
        PlayerListModule playerList = new PlayerListModule(
            this,
            userRepository,
            bindingRepo,
            playerSessions,
            authModule,
            this.config.playerList(),
            new PlayerListRenderer(variables),
            playerContexts,
            canonicalUuidResolver,
            knownMinecraftUuidsResolver);
        VelocityMessageBridge messageBridge = new VelocityMessageBridge(this, this.proxy, this.eventBus);
        this.moduleManager.register(uworldModule);
        this.moduleManager.register(floodgate);
        this.moduleManager.register(authModule);
        this.moduleManager.register(new UworldDiagnostics(this, uworldModule, this.config.uworld()));
        this.moduleManager.register(backendBridge);
        this.moduleManager.register(new PlayerSessionModule(
            this, playerSessions, knownMinecraftUuidsResolver));
        this.moduleManager.register(skinBridge);
        this.moduleManager.register(messageBridge);
        this.moduleManager.register(new YggdrasilModule(this, this.eventBus, YggdrasilModule.Config.defaultConfig()));
        this.moduleManager.register(new UniAuthModule(this, this.eventBus, this.config.uniauth()));
        this.moduleManager.register(playerList);
        this.moduleManager.register(new TabIntegrationModule(this, playerList, variables));
        MigrationModule migrationModule = new MigrationModule(this, this.eventBus, MigrationModule.Config.defaultConfig(), userRepository, uniAuthClient);
        this.moduleManager.register(migrationModule);
        this.moduleManager.register(new MigrationCommands(this, userRepository, migrationModule, uniAuthClient));
        StarxConfig.ModuleConfig motdConfig = this.config.modules().getOrDefault(
            "starx.motd", new StarxConfig.ModuleConfig(true));
        this.moduleManager.register(new MotdModule(this, this.eventBus, MotdModule.Config.from(motdConfig)));
        MaintenanceModule maintenanceModule = new MaintenanceModule(
            this,
            this.eventBus,
            messageBridge,
            backendBridge,
            MaintenanceModule.Config.defaultConfig(),
            new MaintenanceStateService(new JdbcRuntimeSettingRepository(defaultDataSource)));
        this.moduleManager.register(maintenanceModule);
        VelocityWebsiteSync websiteSync = new VelocityWebsiteSync(
            this, backendBridge, maintenanceModule, userRepository, accountIdentities);
        this.lifecycle.own("website synchronization", websiteSync::close);
        this.moduleManager.register(new ChatModule(this, messageBridge, ChatModule.Config.defaultConfig()));
        this.moduleManager.register(new RedirectModule(this, RedirectModule.Config.defaultConfig()));
        QueueModule queueModule = new QueueModule(
            this,
            QueueModule.Config.defaultConfig(),
            new QueueService(),
            new BackendRoutingService(backendBridge.registry()));
        this.moduleManager.register(queueModule);
        this.moduleManager.register(new TutorialModule(
            this,
            TutorialModule.Config.defaultConfig(),
            new JdbcTutorialProgressRepository(defaultDataSource),
            canonicalUuidResolver,
            knownMinecraftUuidsResolver));
        this.moduleManager.register(new HubCommandModule(
            this,
            HubCommandModule.Config.enabled(this.config.uworld().auth().targetServer())));
        this.moduleManager.register(new ReconnectModule(
            this,
            ReconnectModule.Config.defaultConfig(),
            new BackendRoutingService(backendBridge.registry())));
        this.moduleManager.register(new ProxyInfoModule(this, ProxyInfoModule.Config.defaultConfig()));
        this.moduleManager.register(new ForgeCompatModule(this, ForgeCompatModule.Config.from(
            this.config.modules().get("starx.forge"))));
        this.moduleManager.register(new OnlineSyncModule(this, OnlineSyncModule.Config.defaultConfig()));
        this.moduleManager.register(new EnhancedProxyModule(this, EnhancedProxyModule.Config.simpleDefault()));
        this.moduleManager.register(new FileCleanerModule(this, FileCleanerModule.Config.defaultConfig()));
        this.moduleManager.register(new RakNetModule(this, RakNetModule.Config.from(
            this.config.modules().get("starx.proxytools.raknet"))));
        this.moduleManager.register(new BotFilterModule(this, this.eventBus, BotFilterModule.Config.defaultConfig()));
        this.moduleManager.register(new CrashFixModule(this, this.eventBus, CrashFixModule.Config.defaultConfig()));
        this.moduleManager.register(new RiskModule(this, this.eventBus, RiskModule.Config.defaultConfig()));
        this.moduleManager.register(new AnticheatModule(this, this.eventBus, AnticheatModule.Config.defaultConfig()));
        this.moduleManager.register(new BlossomGuardModule(this));
        this.moduleManager.register(new SmartRateLimitModule(this, this.eventBus));
        this.moduleManager.register(new SmartAlertModule(this, this.eventBus));
        SmartQueueModule smartQueueModule = new SmartQueueModule(
            this,
            SmartQueueModule.Config.defaultConfig(),
            new SmartQueueService(),
            new BackendRoutingService(backendBridge.registry()));
        this.moduleManager.register(smartQueueModule);
        this.moduleManager.register(new QqIntegrationModule(this, this.webhookClient, QqIntegrationModule.Config.defaultConfig()));
        PlanIntegrationModule planIntegration = new PlanIntegrationModule(this, this.eventBus, messageBridge, PlanIntegrationModule.Config.defaultConfig());
        this.moduleManager.register(planIntegration);
        this.moduleManager.register(new MapModIntegrationModule(this, MapModIntegrationModule.Config.from(
            this.config.modules().get("starx.integrations.mapmod"))));
        this.moduleManager.register(new LuckPermsContextModule(
            this, bindingRepo, canonicalUuidResolver, knownMinecraftUuidsResolver));
        this.moduleManager.register(new WelcomeModule(this, userRepository, authModule.authService()));
        JdbcPunishmentRepository punishmentRepo = new JdbcPunishmentRepository(defaultDataSource);
        JdbcStaffNoteRepository staffNoteRepo = new JdbcStaffNoteRepository(defaultDataSource);
        JdbcReportRepository reportRepo = new JdbcReportRepository(defaultDataSource);
        JdbcAnnouncementRepository announcementRepo = new JdbcAnnouncementRepository(defaultDataSource);
        JdbcVoteRepository voteRepo = new JdbcVoteRepository(defaultDataSource);
        this.moduleManager.register(new NapCatModule(
            this, bindingRepo, bindingVerification, this.config.napcat(),
            canonicalUuidResolver, knownMinecraftUuidsResolver));
        this.moduleManager.register(new VoteModule(
            this, voteRepo, canonicalUuidResolver, knownMinecraftUuidsResolver));
        this.httpApiServer = new HttpApiServer(this.config, this.eventBus, this.proxy,
            backendBridge.registry(), userRepository, authModule.authService(),
            authModule::approveWebLogin, skinBridge, punishmentRepo, staffNoteRepo,
            reportRepo, announcementRepo, bindingRepo, bindingVerification, voteRepo, () -> {
            Map<String, Object> metrics = new java.util.LinkedHashMap<>(planIntegration.getSummary());
            metrics.put("maintenance", maintenanceModule.isEnabled());
            metrics.put("queue", queueModule.runtimeSnapshot());
            metrics.put("smartQueue", smartQueueModule.runtimeSnapshot());
            metrics.put("networkAutomation", this.networkAutomationService == null
                ? Map.of("status", "not_started")
                : this.networkAutomationService.snapshot());
            return Map.copyOf(metrics);
        }, backendBridge.commandMailbox(), backendBridge::acceptHttpMessage, incidentTimeline, playerSessions,
            accountDeletions, accountEraser, crossDeviceApprovals, bindingChallenges,
            accountByConnectedPlayer, identityAwareUserResolver,
            identityAwareUsernameResolver, identityAwareFullUsernameResolver,
            canonicalUuidResolver, knownMinecraftUuidsResolver);
        this.lifecycle.own("HTTP API", this.httpApiServer::stop);
        this.moduleManager.register(new AdminCommandsModule(
            this, userRepository, punishmentRepo, staffNoteRepo, reportRepo, announcementRepo,
            bindingRepo, bindingVerification, authModule.authService(),
            knownMinecraftUuidsResolver, canonicalUuidResolver));
        WebhookEventPublisher webhookPublisher = new WebhookEventPublisher(this.eventBus, this.webhookClient);
        webhookPublisher.register();
        this.webhookClient.replayPending().whenComplete((ignored, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "StarX webhook outbox replay failed; pending events remain on disk", error);
            }
        });
        this.lifecycle.own("webhook publisher", webhookPublisher::close);
        this.moduleManager.enableAll();
        websiteSync.start();
        authFallback.release();
        java.util.OptionalInt leasedHttpPort =
            this.config.http().portConflictPolicy().usesLease()
                ? this.runtimeEndpointRegistry.leasedPort(this.config.http().port())
                : java.util.OptionalInt.empty();
        TcpPortAllocator.Selection httpPortSelection =
            this.httpApiServer.start(leasedHttpPort);
        this.runtimeEndpointRegistry.publish(
            this.config.http(),
            this.httpApiServer.effectiveHttp(),
            httpPortSelection);
        RuntimePortConfiguration.Result runtimePorts = RuntimePortConfiguration.resolve(
            this.config.http(),
            this.httpApiServer.effectiveHttp(),
            this.config.networkAutomation(),
            httpPortSelection);
        Map<String, Object> runtimePortReport =
            new java.util.LinkedHashMap<>(runtimePorts.snapshot());
        runtimePortReport.put("runtimeEndpoint", Map.of(
            "endpointFile",
            this.runtimeEndpointRegistry.endpointFile().getFileName().toString(),
            "lockFile",
            this.runtimeEndpointRegistry.lockFile().getFileName().toString(),
            "leaseFile",
            this.runtimeEndpointRegistry.leaseFile().getFileName().toString()));
        this.networkAutomationService = new NetworkAutomationService(
            runtimePorts.networkAutomation(),
            runtimePorts.http(),
            this.dataDirectory,
            this.logger,
            Map.copyOf(runtimePortReport));
        this.lifecycle.own("network automation", this.networkAutomationService::close);
        this.networkAutomationService.start();
        this.initializeUpdateManager();
        this.logger.info("StarX Velocity \u521d\u59cb\u5316\u5b8c\u6210");
    }

    /** 初始化插件自动更新检查器 */
    private void initializeUpdateManager() {
        UpdateConfig updateConfig = this.config.update();
        if (!updateConfig.enabled()) {
            return;
        }
        io.github.addxiaoyi.starx.common.update.RepositoryClient repository;
        if (updateConfig.isGitHubSource()) {
            String owner = updateConfig.githubOwner();
            String repo = updateConfig.githubRepo();
            if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
                this.logger.warning("Update source: GitHub owner/repo not configured");
                return;
            }
            repository = new io.github.addxiaoyi.starx.common.update.GitHubReleasesClient(
                "starx-update", owner, repo);
        } else {
            String group = updateConfig.mavenGroup();
            String artifact = updateConfig.mavenArtifact();
            if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
                this.logger.warning("Update source: Maven group/artifact not configured");
                return;
            }
            repository = new io.github.addxiaoyi.starx.common.update.MavenCentralClient(
                "starx-update", group, artifact);
        }
        String currentVersion = this.implementationVersion();
        this.updateManager = new io.github.addxiaoyi.starx.common.update.UpdateManager(
            currentVersion, repository,
            this.dataDirectory.resolve("updates"),
            this.logger::info);
        long intervalMinutes = updateConfig.checkInterval().toMinutes();
        boolean notifyEnabled = updateConfig.notifyEnabled();
        this.proxy.getScheduler().buildTask(this, () -> {
            io.github.addxiaoyi.starx.common.update.UpdateManager.CheckResult result = this.updateManager.checkAndUpdate();
            switch (result) {
                case UPDATE_DOWNLOADED -> {
                    this.logger.info(
                        "StarX update downloaded; restart to apply");
                    if (notifyEnabled) {
                        notifyUpdate("StarX 已下载更新至 "
                            + this.updateManager.latestKnownVersion()
                            + "，请重启服务器以应用");
                    }
                }
                case UPDATE_AVAILABLE -> {
                    this.logger.info(
                        "New version " + this.updateManager.latestKnownVersion() + " available");
                    if (notifyEnabled) {
                        notifyUpdate("StarX 可用更新至 "
                            + this.updateManager.latestKnownVersion()
                            + "，服务器将在下次重启时自动更新");
                    }
                }
                case CHECK_FAILED -> this.logger.fine("Update check failed");
                default -> { /* UP_TO_DATE, DOWNLOAD_FAILED */ }
            }
        }).repeat(java.time.Duration.ofMinutes(intervalMinutes)).schedule();
        this.lifecycle.own("update manager", () -> this.updateManager = null);
    }

    private String implementationVersion() {
        return this.proxy.getPluginManager().fromInstance(this)
            .flatMap(container -> container.getDescription().getVersion())
            .orElse("unknown");
    }

    private void notifyUpdate(String message) {
        this.proxy.getAllPlayers().forEach(player -> 
            player.sendMessage(Component.text(message, NamedTextColor.YELLOW)));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        this.logger.info("StarX Velocity \u6b63\u5728\u5173\u95ed...");
        try {
            this.lifecycle.close();
        } catch (RuntimeException error) {
            this.logger.log(Level.SEVERE, "One or more StarX resources failed to stop", error);
        }
        this.logger.info("StarX Velocity \u5df2\u5173\u95ed");
    }
}
