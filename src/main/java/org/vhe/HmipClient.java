package org.vhe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A lightweight WebSocket client configured for the Homematic IP Connect API.
 * Automatically handles TLS connections to self-signed HCU certificates,
 * authentication headers, and request/response correlation.
 */
public class HmipClient {
    private WebSocketClient ws;
    private final String host;
    private final String pluginId;
    private final String authToken;

    // Shared ObjectMapper for optimal memory usage
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, CompletableFuture<HmipMessage>> messageHandlers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<HmipMessage>>> eventListeners = new ConcurrentHashMap<>();

    public HmipClient(String host, String pluginId, String authToken) {
        this.host = host;
        this.pluginId = pluginId;
        this.authToken = authToken;
    }

    public String getPluginId() {
        return pluginId;
    }

    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            URI uri = new URI("wss://" + host + ":9001");
            System.out.print("Connecting to HCU at " + uri + "...\n");

            ws = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.print("Connected to HCU WebSocket\n");
                    future.complete(null);
                }

                @Override
                public void onMessage(String message) {
                    try {
                        HmipMessage msg = MAPPER.readValue(message, HmipMessage.class);
                        handleMessage(msg);
                    } catch (Exception e) {
                        System.err.print("Failed to parse message: " + e.getMessage() + "\n");
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.print("Disconnected from HCU WebSocket (code=" + code + ", reason=" + reason + ")\n");
                    
                    if (code != 1000) {
                        System.out.print("Attempting to reconnect in 5 seconds...\n");
                        reconnectScheduler.schedule(() -> {
                            try {
                                ws.reconnectBlocking();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                System.err.print("Reconnect failed: " + e.getMessage() + "\n");
                            }
                        }, 5, TimeUnit.SECONDS);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    System.err.print("WebSocket error: " + ex.getMessage() + "\n");
                    if (!future.isDone()) {
                        future.completeExceptionally(ex);
                    }
                }
            };

            // Set headers
            ws.addHeader("authtoken", authToken);
            ws.addHeader("plugin-id", pluginId);
            ws.addHeader("hmip-system-events", "true");

            // Ping server every 30 seconds to keep connection alive
            ws.setConnectionLostTimeout(30);

            // Ignore SSL errors (self-signed cert)
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new javax.net.ssl.X509ExtendedTrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {}
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {}
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
            ws.setSocketFactory(sslContext.getSocketFactory());

            ws.connect();

        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void handleMessage(HmipMessage message) {
        if (message.getId() != null && messageHandlers.containsKey(message.getId())) {
            messageHandlers.remove(message.getId()).complete(message);
            return;
        }

        if (message.getType() != null && eventListeners.containsKey(message.getType())) {
            for (Consumer<HmipMessage> listener : eventListeners.get(message.getType())) {
                listener.accept(message);
            }
        }
    }

    public void on(String type, Consumer<HmipMessage> callback) {
        eventListeners.computeIfAbsent(type, k -> new ArrayList<>()).add(callback);
    }

    public String send(String type, Object body) {
        return send(type, body, UUID.randomUUID().toString());
    }

    public String send(String type, Object body, String id) {
        try {
            JsonNode bodyNode = MAPPER.valueToTree(body);
            HmipMessage message = new HmipMessage(id, type, pluginId, bodyNode);
            ws.send(MAPPER.writeValueAsString(message));
            return id;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public CompletableFuture<HmipMessage> sendRequest(String type, Object body) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<HmipMessage> future = new CompletableFuture<>();
        messageHandlers.put(id, future);
        send(type, body, id);
        return future;
    }

    public CompletableFuture<JsonNode> getSystemState() {
        return sendRequest("HMIP_SYSTEM_REQUEST", Map.of(
            "path", "/hmip/home/getSystemState",
            "body", Map.of()
        )).thenApply(HmipMessage::getBody);
    }

    public void sendStatusEvent(String deviceId, List<Object> features) {
        send("STATUS_EVENT", Map.of(
            "deviceId", deviceId,
            "features", features
        ));
    }

    /**
     * Shuts down internal schedulers and disconnects the WebSocket.
     */
    public void shutdown() {
        if (!reconnectScheduler.isShutdown()) {
            reconnectScheduler.shutdown();
        }
        if (ws != null && ws.isOpen()) {
            ws.close();
        }
    }
}
