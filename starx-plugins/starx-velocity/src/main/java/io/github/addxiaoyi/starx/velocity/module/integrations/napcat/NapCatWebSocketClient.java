/*
 * Decompiled with CFR 0.152.
 */
package io.github.addxiaoyi.starx.velocity.module.integrations.napcat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NapCatWebSocketClient {
    private static final int MAX_EVENT_CHARS = 64 * 1024;
    private static final Logger log = LoggerFactory.getLogger(NapCatWebSocketClient.class);
    private static final int MAX_RECONNECT_DELAY_MS = 30000;
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;
    private final String wsUrl;
    private final String httpUrl;
    private final MessageHandler messageHandler;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;
    private final AtomicBoolean running;
    private WebSocket webSocket;
    private int reconnectAttempts;

    public NapCatWebSocketClient(String wsUrl, String httpUrl, MessageHandler messageHandler) {
        this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl");
        this.httpUrl = httpUrl != null && !httpUrl.isBlank() ? httpUrl : NapCatWebSocketClient.httpUrlFromWs(wsUrl);
        this.messageHandler = Objects.requireNonNull(messageHandler, "messageHandler");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "napcat-ws");
            t.setDaemon(true);
            return t;
        });
        this.gson = new Gson();
        this.running = new AtomicBoolean(false);
    }

    private static String httpUrlFromWs(String wsUrl) {
        return wsUrl.replace("ws://", "http://").replace("wss://", "https://").replaceAll(":\\d+$", ":3000");
    }

    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.reconnectAttempts = 0;
            this.doConnect();
        }
    }

    public synchronized void stop() {
        this.running.set(false);
        if (this.webSocket != null) {
            this.webSocket.sendClose(1000, "Shutdown");
            this.webSocket = null;
        }
        this.scheduler.shutdownNow();
    }

    public void sendPrivateMessage(long userId, String message) {
        this.sendApiCall("send_private_msg", Map.of("user_id", userId, "message", message));
    }

    public void sendGroupMessage(long groupId, String message) {
        this.sendApiCall("send_group_msg", Map.of("group_id", groupId, "message", message));
    }

    private void sendApiCall(String action, Map<String, Object> params) {
        String json = this.gson.toJson(params);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(this.httpUrl + "/" + action)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(5L)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
        this.httpClient.sendAsync(request, apiResponseHandler()).exceptionally(t -> {
            log.warn("NapCat HTTP API call failed: {} - {}", (Object)action, (Object)t.getMessage());
            return null;
        });
    }

    static HttpResponse.BodyHandler<Void> apiResponseHandler() {
        return HttpResponse.BodyHandlers.discarding();
    }

    private void doConnect() {
        if (!this.running.get()) {
            return;
        }
        log.info("Connecting to NapCat at {} ...", (Object)this.wsUrl);
        this.httpClient.newWebSocketBuilder().buildAsync(URI.create(this.wsUrl), new OneBotListener()).thenAccept(ws -> {
            if (this.acceptConnectedSocket(ws)) {
                log.info("Connected to NapCat WebSocket: {}", (Object)this.wsUrl);
            }
        }).exceptionally(t -> {
            log.warn("Failed to connect to NapCat (attempt {}): {}", (Object)(this.reconnectAttempts + 1), (Object)t.getMessage());
            this.scheduleReconnect();
            return null;
        });
    }

    synchronized boolean acceptConnectedSocket(WebSocket socket) {
        Objects.requireNonNull(socket, "socket");
        if (!this.running.get()) {
            socket.sendClose(1000, "Shutdown");
            return false;
        }
        this.webSocket = socket;
        this.reconnectAttempts = 0;
        return true;
    }

    synchronized boolean releaseConnectedSocket(WebSocket socket) {
        if (this.webSocket != socket) {
            return false;
        }
        this.webSocket = null;
        return true;
    }

    private void scheduleReconnect() {
        if (!this.running.get()) {
            return;
        }
        long delay = Math.min((long)(1000.0 * Math.pow(2.0, Math.min(this.reconnectAttempts, 5))), 30000L);
        ++this.reconnectAttempts;
        log.info("Reconnecting to NapCat in {}ms (attempt {})", (Object)delay, (Object)this.reconnectAttempts);
        this.scheduler.schedule(this::doConnect, delay, TimeUnit.MILLISECONDS);
    }

    private void handleEvent(String json) {
        JsonObject sender;
        JsonObject obj = this.gson.fromJson(json, JsonObject.class);
        if (obj == null) {
            return;
        }
        String postType = NapCatWebSocketClient.getString(obj, "post_type");
        if (!"message".equals(postType)) {
            return;
        }
        String messageType = NapCatWebSocketClient.getString(obj, "message_type");
        String rawMessage = NapCatWebSocketClient.getString(obj, "raw_message");
        if (rawMessage.isEmpty()) {
            rawMessage = NapCatWebSocketClient.getString(obj, "message");
        }
        if (rawMessage.isEmpty()) {
            return;
        }
        long userId = NapCatWebSocketClient.getLong(obj, "user_id");
        String nickname = "Unknown";
        if (obj.has("sender") && obj.get("sender").isJsonObject() && (nickname = NapCatWebSocketClient.getString(sender = obj.getAsJsonObject("sender"), "nickname")).isEmpty()) {
            nickname = NapCatWebSocketClient.getString(sender, "card");
        }
        if ("private".equals(messageType)) {
            this.messageHandler.onPrivateMessage(userId, rawMessage, nickname);
        } else if ("group".equals(messageType)) {
            long groupId = NapCatWebSocketClient.getLong(obj, "group_id");
            this.messageHandler.onGroupMessage(groupId, userId, rawMessage, nickname);
        }
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static long getLong(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
    }

    public static interface MessageHandler {
        public void onPrivateMessage(long var1, String var3, String var4);

        public void onGroupMessage(long var1, long var3, String var5, String var6);
    }

    private final class OneBotListener
    implements WebSocket.Listener {
        private final NapCatFrameBuffer buffer = new NapCatFrameBuffer(MAX_EVENT_CHARS);

        private OneBotListener() {
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            Optional<String> completed = this.buffer.append(data, last);
            if (completed.isPresent()) {
                try {
                    NapCatWebSocketClient.this.handleEvent(completed.get());
                }
                catch (Exception e) {
                    log.warn("Failed to handle NapCat event: {}", (Object)e.getMessage());
                }
            }
            ws.request(1L);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("NapCat WebSocket error: {}", (Object)error.getMessage());
            if (NapCatWebSocketClient.this.releaseConnectedSocket(ws)) {
                NapCatWebSocketClient.this.scheduleReconnect();
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            log.info("NapCat WebSocket closed: {} {}", (Object)status, (Object)reason);
            if (NapCatWebSocketClient.this.releaseConnectedSocket(ws)) {
                NapCatWebSocketClient.this.scheduleReconnect();
            }
            return null;
        }
    }
}
