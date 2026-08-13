package io.github.addxiaoyi.starx.velocity.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.addxiaoyi.starx.api.event.EventBus;
import io.github.addxiaoyi.starx.common.auth.AuthService;
import io.github.addxiaoyi.starx.common.auth.AuthLease;
import io.github.addxiaoyi.starx.common.auth.BindingVerificationService;
import io.github.addxiaoyi.starx.common.auth.EmailChallengeService;
import io.github.addxiaoyi.starx.common.auth.CrossDeviceApprovalService;
import io.github.addxiaoyi.starx.common.crypto.HmacSigner;
import io.github.addxiaoyi.starx.common.crypto.HmacRequestSigner;
import io.github.addxiaoyi.starx.common.database.JdbcAnnouncementRepository;
import io.github.addxiaoyi.starx.common.database.JdbcBindingRepository;
import io.github.addxiaoyi.starx.common.database.JdbcPunishmentRepository;
import io.github.addxiaoyi.starx.common.database.JdbcReportRepository;
import io.github.addxiaoyi.starx.common.database.JdbcStaffNoteRepository;
import io.github.addxiaoyi.starx.common.database.JdbcUserRepository;
import io.github.addxiaoyi.starx.common.database.JdbcVoteRepository;
import io.github.addxiaoyi.starx.common.session.JdbcPlayerSessionRepository;
import io.github.addxiaoyi.starx.common.account.JdbcAccountDeletionRepository;
import io.github.addxiaoyi.starx.common.account.JdbcAccountErasureRepository;
import io.github.addxiaoyi.starx.velocity.config.StarxConfig;
import io.github.addxiaoyi.starx.velocity.bridge.BackendNodeRegistry;
import io.github.addxiaoyi.starx.velocity.bridge.BackendCommandMailbox;
import io.github.addxiaoyi.starx.api.bridge.BridgeMessage;
import io.github.addxiaoyi.starx.velocity.http.admin.*;
import io.github.addxiaoyi.starx.velocity.module.skin.SkinBridgeModule;
import io.github.addxiaoyi.starx.velocity.operations.IncidentTimeline;
import io.github.addxiaoyi.starx.velocity.network.TcpPortAllocator;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.time.Clock;
import java.time.Instant;
import java.lang.management.ManagementFactory;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.UUID;
import io.github.addxiaoyi.starx.common.binding.BindingChallengeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpApiServer implements RouteRegistrar {
    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String SIGNATURE_HEADER = "X-StarX-Signature";
    public static final String TIMESTAMP_HEADER = "X-StarX-Timestamp";
    private static final int MAX_FORWARDED_IP_LENGTH = 128;
    private static final long MAX_TIMESTAMP_DRIFT_MS = 300000L;
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of("/v1/health");
    private static final Logger log = LoggerFactory.getLogger(HttpApiServer.class);
    private final StarxConfig config;
    private final EventBus eventBus;
    private final ProxyServer proxy;
    private final BackendNodeRegistry backendNodes;
    private final JdbcUserRepository userRepository;
    private final AuthService authService;
    private final BiFunction<UUID, AuthLease, Boolean> webLoginApprover;
    private final SkinBridgeModule skinBridge;
    private final JdbcPunishmentRepository punishmentRepo;
    private final JdbcStaffNoteRepository staffNoteRepo;
    private final JdbcReportRepository reportRepo;
    private final JdbcAnnouncementRepository announcementRepo;
    private final JdbcBindingRepository bindingRepo;
    private final BindingVerificationService bindingVerification;
    private final JdbcVoteRepository voteRepo;
    private final Supplier<Map<String, Object>> networkMetricsSupplier;
    private final BackendCommandMailbox backendCommands;
    private final Consumer<BridgeMessage> backendMessageConsumer;
    private final IncidentTimeline incidentTimeline;
    private final JdbcPlayerSessionRepository playerSessions;
    private final JdbcAccountDeletionRepository accountDeletions;
    private final JdbcAccountErasureRepository accountEraser;
    private final CrossDeviceApprovalService crossDeviceApprovals;
    private final BindingChallengeService bindingChallenges;
    private final Function<UUID, String> accountIdResolver;
    private final Map<String, Map<String, RouteHandler>> routes = new HashMap<>();
    private final BoundedRateLimitRegistry rateLimits = new BoundedRateLimitRegistry(
            4096, RATE_LIMIT_MAX_REQUESTS, RATE_LIMIT_WINDOW_MS, Clock.systemUTC());
    private final BoundedRateLimitRegistry sensitiveRateLimits = new BoundedRateLimitRegistry(
            4096, SENSITIVE_RATE_LIMIT_MAX, RATE_LIMIT_WINDOW_MS, Clock.systemUTC());
    private HttpServer server;
    private ExecutorService executor;
    private StarxConfig.HttpConfig effectiveHttp;
    private TcpPortAllocator.Selection portSelection;
    private static final long RATE_LIMIT_WINDOW_MS = 60000L;
    private static final int RATE_LIMIT_MAX_REQUESTS = 100;
    private static final int SENSITIVE_RATE_LIMIT_MAX = 10;
    private static final int HMAC_REPLAY_CAPACITY = 8192;
    private final HmacReplayGuard hmacReplayGuard = new HmacReplayGuard(HMAC_REPLAY_CAPACITY);

    public HttpApiServer(
            StarxConfig config,
            EventBus eventBus,
            ProxyServer proxy,
            BackendNodeRegistry backendNodes,
            JdbcUserRepository userRepository,
            AuthService authService,
            BiFunction<UUID, AuthLease, Boolean> webLoginApprover,
            SkinBridgeModule skinBridge,
            JdbcPunishmentRepository punishmentRepo,
            JdbcStaffNoteRepository staffNoteRepo,
            JdbcReportRepository reportRepo,
            JdbcAnnouncementRepository announcementRepo,
            JdbcBindingRepository bindingRepo,
            BindingVerificationService bindingVerification,
            JdbcVoteRepository voteRepo,
            Supplier<Map<String, Object>> networkMetricsSupplier,
            BackendCommandMailbox backendCommands,
            Consumer<BridgeMessage> backendMessageConsumer,
            IncidentTimeline incidentTimeline,
            JdbcPlayerSessionRepository playerSessions,
            JdbcAccountDeletionRepository accountDeletions,
            JdbcAccountErasureRepository accountEraser,
            CrossDeviceApprovalService crossDeviceApprovals,
            BindingChallengeService bindingChallenges,
            Function<UUID, String> accountIdResolver) {
        this.config = Objects.requireNonNull(config, "config");
        this.effectiveHttp = this.config.http();
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.backendNodes = Objects.requireNonNull(backendNodes, "backendNodes");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.authService = authService;
        this.webLoginApprover = Objects.requireNonNull(
                webLoginApprover, "webLoginApprover");
        this.skinBridge = skinBridge;
        this.punishmentRepo = Objects.requireNonNull(punishmentRepo, "punishmentRepo");
        this.staffNoteRepo = Objects.requireNonNull(staffNoteRepo, "staffNoteRepo");
        this.reportRepo = Objects.requireNonNull(reportRepo, "reportRepo");
        this.announcementRepo = Objects.requireNonNull(announcementRepo, "announcementRepo");
        this.bindingRepo = Objects.requireNonNull(bindingRepo, "bindingRepo");
        this.bindingVerification = Objects.requireNonNull(bindingVerification, "bindingVerification");
        this.voteRepo = Objects.requireNonNull(voteRepo, "voteRepo");
        this.networkMetricsSupplier = Objects.requireNonNull(networkMetricsSupplier, "networkMetricsSupplier");
        this.backendCommands = Objects.requireNonNull(backendCommands, "backendCommands");
        this.backendMessageConsumer = Objects.requireNonNull(
                backendMessageConsumer, "backendMessageConsumer");
        this.incidentTimeline = Objects.requireNonNull(incidentTimeline, "incidentTimeline");
        this.playerSessions = Objects.requireNonNull(playerSessions, "playerSessions");
        this.accountDeletions = Objects.requireNonNull(accountDeletions, "accountDeletions");
        this.accountEraser = Objects.requireNonNull(accountEraser, "accountEraser");
        this.crossDeviceApprovals = Objects.requireNonNull(
                crossDeviceApprovals, "crossDeviceApprovals");
        this.bindingChallenges = Objects.requireNonNull(bindingChallenges, "bindingChallenges");
        this.accountIdResolver = Objects.requireNonNull(accountIdResolver, "accountIdResolver");
    }

    public TcpPortAllocator.Selection start() throws IOException {
        return start(OptionalInt.empty());
    }

    public TcpPortAllocator.Selection start(OptionalInt leasedPort) throws IOException {
        if (this.server != null && this.portSelection != null) {
            return this.portSelection;
        }
        this.portSelection = bindAvailableHttpServer(
                leasedPort == null ? OptionalInt.empty() : leasedPort);
        if (this.portSelection.changed()) {
            log.warn(
                    "Configured HTTP API port {} is occupied; selected unused port {} (mode={}, occupied={})",
                    this.portSelection.preferredPort(),
                    this.portSelection.selectedPort(),
                    this.portSelection.mode(),
                    this.portSelection.occupiedPorts());
        }
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "starx-http");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                2, 16, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), threads,
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.server.setExecutor(this.executor);
        this.get("/v1/health", this::health);
        RouteHandler requireAuth = this.requireApiKey();
        new UserQueryHandler(this.userRepository).register(this, requireAuth);
        new UserOverviewHandler(this.userRepository, this.bindingRepo, this.playerSessions)
                .register(this, requireAuth);
        new SkinRefreshHandler(this.skinBridge, this.userRepository).register(this, requireAuth);
        if (this.authService != null) {
            RouteHandler sensitiveAuth = this.requireSensitiveAuth();
            new PasswordResetHandler(this.authService).register(this, sensitiveAuth);
            new TotpEnableHandler(this.authService).register(this, sensitiveAuth);
            io.github.addxiaoyi.starx.common.auth.EmailSender emailSender;
            try {
                emailSender = new WebhookEmailSender(this.config.webhook());
            } catch (IllegalArgumentException error) {
                log.info("Email challenge disabled because webhook delivery is not configured: {}", error.getMessage());
                emailSender = (email, code) -> { throw new IllegalStateException(error.getMessage()); };
            }
            new EmailChallengeHandler(
                new EmailChallengeService(
                    emailSender,
                    java.time.Duration.ofMinutes(10),
                    this.bindingChallenges,
                    this.accountIdResolver,
                    java.time.Clock.systemUTC()),
                this.authService).register(this, sensitiveAuth);
            try {
                new CrossDeviceApprovalHandler(
                        this.crossDeviceApprovals,
                        WebsiteOriginResolver.fromWebhook(this.config.webhook()),
                        new CrossDeviceActionExecutor(
                                (playerId, email) -> this.authService.bindEmail(playerId, email).success(),
                                (playerId, username) -> this.skinBridge != null
                                        && this.skinBridge.refreshSkinFromWebsite(playerId, username),
                                this.webLoginApprover))
                        .register(this, sensitiveAuth);
            } catch (IllegalArgumentException error) {
                log.info("Cross-device approval disabled because no public website origin is configured: {}", error.getMessage());
            }
            new DeleteUserHandler(this.authService, this.userRepository, this.accountEraser)
                    .register(this, sensitiveAuth);
            new BindingUnlinkHandler(this.bindingRepo).register(this, sensitiveAuth);
            new AccountDeletionHandler(this.accountDeletions, this.userRepository)
                    .register(this, sensitiveAuth);
        }
        new BanHandler(this.userRepository, this.eventBus, this.punishmentRepo).register(this, requireAuth);
        new KickHandler(this.proxy, this.eventBus).register(this, requireAuth);
        new LinkExternalUserHandler(this.userRepository, this.eventBus).register(this, requireAuth);
        new PunishmentHandler(this.punishmentRepo).register(this, requireAuth);
        new StaffNoteHandler(this.staffNoteRepo).register(this, requireAuth);
        new ReportHandler(this.reportRepo).register(this, requireAuth);
        new AnnouncementHandler(this.announcementRepo).register(this, requireAuth);
        new BindingHandler(this.bindingRepo, this.userRepository, this.bindingVerification).register(this, requireAuth);
        new VoteHandler(this.voteRepo).register(this, requireAuth);
        new BackendProbeHandler(
                name -> this.proxy.getServer(name).isPresent(),
                this.backendCommands).register(this, requireAuth);
        new BackendHeartbeatHandler(
                this.backendNodes,
                name -> this.proxy.getServer(name).isPresent(),
                java.time.Clock.systemUTC(),
                this.backendCommands,
                this.backendMessageConsumer).register(this, requireAuth);
        new NetworkStatusHandler(this.proxy, this.backendNodes, this.networkMetricsSupplier).register(this, requireAuth);
        new IncidentTimelineHandler(this.incidentTimeline).register(this, requireAuth);
        new BridgePingHandler(java.time.Clock.systemUTC()).register(this, requireAuth);
        this.server.start();
        log.info("HTTP API server started on {}:{} (secured)", this.effectiveHttp.bind(), this.effectiveHttp.port());
        this.logExposure();
        return this.portSelection;
    }

    public StarxConfig.HttpConfig effectiveHttp() {
        return this.effectiveHttp;
    }

    private TcpPortAllocator.Selection bindAvailableHttpServer(
            OptionalInt leasedPort) throws IOException {
        Set<Integer> unavailablePorts = new LinkedHashSet<>();
        BindException lastRace = null;
        int preferredPort = this.config.http().port();
        StarxConfig.HttpConfig.PortConflictPolicy policy =
                this.config.http().portConflictPolicy();

        if (policy == StarxConfig.HttpConfig.PortConflictPolicy.STRICT) {
            try {
                int actualPort = bindPort(preferredPort);
                return new TcpPortAllocator.Selection(
                        preferredPort, actualPort, java.util.List.of(), java.util.List.of(), false);
            } catch (BindException occupied) {
                throw new IOException(
                        "Configured HTTP API port " + preferredPort
                                + " is occupied and http.port-conflict-policy is strict",
                        occupied);
            }
        }

        if (policy.usesLease()
                && leasedPort.isPresent()
                && leasedPort.getAsInt() != preferredPort) {
            try {
                int actualPort = bindPort(preferredPort);
                return new TcpPortAllocator.Selection(
                        preferredPort, actualPort, java.util.List.of(), java.util.List.of(), false);
            } catch (BindException occupied) {
                unavailablePorts.add(preferredPort);
                lastRace = occupied;
            }
            int previousPort = leasedPort.getAsInt();
            try {
                int actualPort = bindPort(previousPort);
                log.info(
                        "Configured HTTP API port {} remains occupied; reused leased port {}",
                        preferredPort,
                        actualPort);
                return new TcpPortAllocator.Selection(
                        preferredPort,
                        actualPort,
                        java.util.List.copyOf(unavailablePorts),
                        java.util.List.of(),
                        false);
            } catch (BindException occupied) {
                unavailablePorts.add(previousPort);
                lastRace = occupied;
            }
        }

        for (int attempt = 0; attempt < 16; attempt++) {
            TcpPortAllocator.Selection selection = TcpPortAllocator.select(
                    this.config.http().bind(),
                    preferredPort,
                    unavailablePorts,
                    this.config.http().fallbackRangeStart(),
                    this.config.http().fallbackRangeEnd(),
                    policy.allowsEphemeralFallback());
            try {
                int actualPort = bindPort(selection.selectedPort());
                TcpPortAllocator.Selection bound = new TcpPortAllocator.Selection(
                        selection.preferredPort(),
                        actualPort,
                        selection.occupiedPorts(),
                        selection.reservedPorts(),
                        selection.ephemeralFallback());
                return bound.withAdditionalUnavailable(unavailablePorts);
            } catch (BindException race) {
                unavailablePorts.add(selection.selectedPort());
                lastRace = race;
            }
        }
        throw new IOException("Unable to bind an unused HTTP API port after repeated races", lastRace);
    }

    private int bindPort(int port) throws IOException {
        this.server = HttpServer.create(
                new InetSocketAddress(this.config.http().bind(), port),
                0);
        int actualPort = this.server.getAddress().getPort();
        this.effectiveHttp = new StarxConfig.HttpConfig(
                this.config.http().bind(),
                actualPort,
                this.config.http().frpPublicUrl(),
                this.config.http().portConflictPolicy(),
                this.config.http().fallbackRangeStart(),
                this.config.http().fallbackRangeEnd());
        return actualPort;
    }

    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
            log.info("HTTP API server stopped");
        }
        this.hmacReplayGuard.clear();
    }

    private void logExposure() {
        ApiExposureResolver.Exposure exposure;
        try {
            exposure = ApiExposureResolver.resolve(this.effectiveHttp);
        } catch (SocketException error) {
            log.warn("Unable to inspect local network interfaces; using configured API fallback", error);
            exposure = ApiExposureResolver.resolve(this.effectiveHttp, java.util.List.of());
        }
        ApiConsoleReport.lines(exposure, this.routes, PUBLIC_ENDPOINTS).forEach(log::info);
        if (!exposure.publiclyReachable()) {
            log.info(
                    "StarX API is intentionally local-only; configure http.frp-public-url or a public bind only when external access is required.");
        }
    }

    @Override
    public void get(String path, RouteHandler handler) {
        this.register(path, "GET", handler);
    }

    @Override
    public void get(String path, RouteHandler... handlers) {
        RouteHandler chain = ctx -> {
            for (RouteHandler h : handlers) {
                h.handle(ctx);
            }
        };
        this.register(path, "GET", chain);
    }

    @Override
    public void post(String path, RouteHandler handler) {
        this.register(path, "POST", handler);
    }

    @Override
    public void post(String path, RouteHandler... handlers) {
        RouteHandler chain = ctx -> {
            for (RouteHandler h : handlers) {
                h.handle(ctx);
            }
        };
        this.register(path, "POST", chain);
    }

    private void register(String path, String method, RouteHandler handler) {
        Map<String, RouteHandler> methods = this.routes.computeIfAbsent(path, k -> new HashMap<>());
        methods.put(method, handler);
        if (methods.size() == 1) {
            this.server.createContext(path, this::dispatch);
        }
    }

    private void dispatch(HttpExchange exchange) {
        this.applyCorsHeaders(exchange);
        if (this.handleCors(exchange)) {
            return;
        }
        String clientIp = this.getClientIp(exchange);
        if (!this.rateLimits.tryAcquire(clientIp)) {
            try {
                exchange.sendResponseHeaders(429, -1L);
            } catch (IOException ignored) {}
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();
        Map<String, RouteHandler> methods = this.routes.get(path);
        RouteHandler handler = methods != null ? methods.get(method) : null;
        if (handler == null) {
            try {
                exchange.sendResponseHeaders(405, -1L);
            } catch (IOException ignored) {}
            exchange.close();
            return;
        }
        JsonHttpExchange ctx = new JsonHttpExchange(exchange);
        try {
            if (!PUBLIC_ENDPOINTS.contains(path) && !this.authFilter(ctx)) {
                return;
            }
            handler.handle(ctx);
        } catch (AuthenticationRequiredException ignored) {
            exchange.close();
        } catch (IllegalArgumentException error) {
            if ("Payload too large".equals(error.getMessage())) {
                try {
                    new JsonHttpExchange(exchange).status(413).json(Map.of("error", error.getMessage()));
                } catch (IOException ignored) {
                    exchange.close();
                }
            } else if ("Invalid Content-Length".equals(error.getMessage())
                    || "Invalid query encoding".equals(error.getMessage())) {
                try {
                    new JsonHttpExchange(exchange).status(400).json(Map.of("error", error.getMessage()));
                } catch (IOException ignored) {
                    exchange.close();
                }
            } else {
                try {
                    new JsonHttpExchange(exchange).status(400).json(Map.of("error", "bad_request"));
                } catch (IOException ignored) {
                    exchange.close();
                }
            }
        } catch (Exception e) {
            log.error("Error handling {} {}", method, path, e);
            try {
                ctx.status(500).json(Map.of("error", "Internal Server Error"));
            } catch (IOException ignored) {}
        }
    }

    private boolean handleCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank() && !this.isAllowedOrigin(origin)) {
            try {
                exchange.sendResponseHeaders(403, -1L);
            } catch (IOException ignored) {}
            exchange.close();
            return true;
        }
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            try {
                exchange.sendResponseHeaders(204, -1L);
            } catch (IOException ignored) {}
            exchange.close();
            return true;
        }
        return false;
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && this.isAllowedOrigin(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-API-Key, X-StarX-Signature, X-StarX-Timestamp");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
    }

    private boolean isAllowedOrigin(String origin) {
        String allowed = WebsiteOriginResolver.fromUrl(this.config.auth().bindingWebsiteUrl());
        return allowed.equalsIgnoreCase(origin);
    }

    private String getClientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (this.trustedForwardedIp(exchange)) {
            String clientIp = sanitizeForwardedIp(forwarded);
            if (clientIp != null) {
                return clientIp;
            }
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    private boolean authFilter(JsonHttpExchange ctx) throws IOException {
        String apiKey = this.config.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            ctx.status(503).json(Map.of("error", "API key not configured"));
            return false;
        }
        String signature = ctx.header(SIGNATURE_HEADER);
        String timestamp = ctx.header(TIMESTAMP_HEADER);
        if (signature != null && !signature.isBlank() && timestamp != null && !timestamp.isBlank()) {
            long requestTime;
            long currentTime = System.currentTimeMillis();
            try {
                requestTime = Long.parseLong(timestamp);
                if (requestTime < currentTime - MAX_TIMESTAMP_DRIFT_MS
                        || requestTime > currentTime + MAX_TIMESTAMP_DRIFT_MS) {
                    ctx.status(401).json(Map.of("error", "Request expired or timestamp invalid"));
                    return false;
                }
            } catch (NumberFormatException e) {
                ctx.status(401).json(Map.of("error", "Invalid timestamp format"));
                return false;
            }
            if (HmacRequestSigner.verify(
                    apiKey,
                    ctx.requestMethod(),
                    ctx.requestTarget(),
                    timestamp,
                    ctx.bodyString(),
                    signature)) {
                long expiresAt = requestTime + MAX_TIMESTAMP_DRIFT_MS;
                if (this.hmacReplayGuard.claim(signature, expiresAt, currentTime)) {
                    ctx.markAuthenticated();
                    return true;
                }
                ctx.status(401).json(Map.of("error", "Request signature already used"));
                return false;
            }
        }
        String provided = ctx.header(API_KEY_HEADER);
        if (HmacSigner.constantTimeEquals(apiKey, provided)) {
            ctx.markAuthenticated();
            return true;
        }
        ctx.status(401).json(Map.of("error", "Unauthorized"));
        return false;
    }

    private void health(JsonHttpExchange ctx) throws IOException {
        Runtime runtime = Runtime.getRuntime();
        long committedHeap = runtime.totalMemory();
        long usedHeap = committedHeap - runtime.freeMemory();
        var backendSnapshot = this.backendNodes.all();
        int onlineBackends = Math.toIntExact(backendSnapshot.stream()
                .filter(node -> this.backendNodes.admissionWeight(node.registeredServer()) > 0)
                .count());
        ctx.status(200).json(HealthStatusPayload.from(
                Instant.now(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                this.proxy.getPlayerCount(),
                this.proxy.getAllServers().size(),
                this.backendNodes.serverNames().size(),
                onlineBackends,
                usedHeap,
                committedHeap,
                runtime.maxMemory(),
                runtime.availableProcessors()));
    }

    public RouteHandler requireApiKey() {
        return ctx -> {
            if (ctx.authenticated()) {
                return;
            }
            String apiKey = this.config.apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                ctx.status(503).json(Map.of("error", "API key not configured"));
                throw new AuthenticationRequiredException();
            }
            String provided = ctx.header(API_KEY_HEADER);
            if (!HmacSigner.constantTimeEquals(apiKey, provided)) {
                ctx.status(401).json(Map.of("error", "Unauthorized"));
                throw new AuthenticationRequiredException();
            }
            ctx.markAuthenticated();
        };
    }

    public RouteHandler requireSensitiveAuth() {
        return ctx -> {
            this.requireApiKey().handle(ctx);
            String clientIp = this.getClientIpFromJsonExchange(ctx);
            if (!this.sensitiveRateLimits.tryAcquire(clientIp)) {
                try {
                    ctx.status(429).json(Map.of("error", "Too many requests. Please try again later."));
                } catch (IOException ignored) {}
                throw new AuthenticationRequiredException();
            }
        };
    }

    private String getClientIpFromJsonExchange(JsonHttpExchange ctx) {
        String forwarded = ctx.header("X-Forwarded-For");
        if (trustedForwardedIp(ctx.getRemoteAddress())) {
            String clientIp = sanitizeForwardedIp(forwarded);
            if (clientIp != null) {
                return clientIp;
            }
        }
        InetSocketAddress remote = ctx.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    private boolean trustedForwardedIp(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    private boolean trustedForwardedIp(InetSocketAddress remote) {
        // Only a local reverse proxy may assert the original client address.
        return remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    private static String sanitizeForwardedIp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.split(",", 2)[0].trim();
        if (value.isBlank() || value.length() > MAX_FORWARDED_IP_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return null;
            }
        }
        return value;
    }

    private static class AuthenticationRequiredException extends RuntimeException {
        AuthenticationRequiredException() {}
    }
}
