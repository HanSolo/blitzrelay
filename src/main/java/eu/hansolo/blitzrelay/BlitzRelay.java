package eu.hansolo.blitzrelay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


public class BlitzRelay {

    // ── Configuration ─────────────────────────────────────────

    private static final String   MQTT_HOST  = System.getenv().getOrDefault("MQTT_HOST",  "host.docker.internal");
    private static final int      MQTT_PORT  = Integer.parseInt(System.getenv().getOrDefault("MQTT_PORT",  "1883"));
    private static final String   MQTT_TOPIC = System.getenv().getOrDefault("MQTT_TOPIC", "lightning/strikes");

    private static final List<String> WS_SERVERS = List.of(
    "wss://ws1.blitzortung.org/",
    "wss://ws2.blitzortung.org/",
    "wss://ws7.blitzortung.org/",
    "wss://ws8.blitzortung.org/"
                                                          );

    private static final String HANDSHAKE_MSG = "{\"a\":111}";

    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS      = 60_000;

    // ── State ─────────────────────────────────────────────────

    private static final Logger        log          = LoggerFactory.getLogger(BlitzRelay.class);
    private static final ObjectMapper  JSON         = new ObjectMapper();
    private static final AtomicLong    strikeCount  = new AtomicLong(0);
    private static       MqttClient    mqttClient;
    private static       int           serverIndex  = 0;

    // ── Entry point ───────────────────────────────────────────

    public static void main(final String[] args) throws Exception {
        log.info("BlitzRelay starting…");
        log.info("MQTT: {}:{} topic: {}", MQTT_HOST, MQTT_PORT, MQTT_TOPIC);

        connectMQTT();

        // Log strike count every 60 seconds
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000);
                    log.info("Strikes published in last 60s: {}", strikeCount.getAndSet(0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        connectWithRetry();
    }

    // ── MQTT ──────────────────────────────────────────────────

    private static void connectMQTT() throws Exception {
        final MqttConnectOptions options = new MqttConnectOptions();
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);

        mqttClient = new MqttClient(
        "tcp://" + MQTT_HOST + ":" + MQTT_PORT,
        "blitzrelay-" + UUID.randomUUID().toString().substring(0, 8)
        );
        mqttClient.connect(options);
        log.info("MQTT connected to {}:{}", MQTT_HOST, MQTT_PORT);
    }

    private static void publishStrike(final String json) {
        try {
            mqttClient.publish(MQTT_TOPIC, json.getBytes(StandardCharsets.UTF_8), 0, false);
            strikeCount.incrementAndGet();
        } catch (final Exception e) {
            log.warn("MQTT publish failed: {}", e.getMessage());
        }
    }

    // ── WebSocket ─────────────────────────────────────────────

    private static void connectWithRetry() throws InterruptedException {
        long backoffMs = INITIAL_BACKOFF_MS;

        while (true) {
            final String server = WS_SERVERS.get(serverIndex % WS_SERVERS.size());
            log.info("Connecting to Blitzortung: {}", server);

            try {
                final boolean connected = listenOnce(server);
                if (connected) { backoffMs = INITIAL_BACKOFF_MS; }
            } catch (final Exception e) {
                log.warn("Connection error: {}", e.getMessage());
            }

            serverIndex++;
            log.info("Reconnecting in {}ms…", backoffMs);
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }

    private static boolean listenOnce(final String server) throws Exception {
        final HttpClient httpClient = HttpClient.newBuilder()
                                                .connectTimeout(Duration.ofSeconds(20))
                                                .build();

        final StringBuilder messageBuffer = new StringBuilder();
        final Object        lock          = new Object();
        final boolean[]     closed        = { false };

        final WebSocket ws = httpClient.newWebSocketBuilder()
                                       .connectTimeout(Duration.ofSeconds(20))
                                       .buildAsync(URI.create(server), new WebSocket.Listener() {

                                           @Override
                                           public void onOpen(final WebSocket ws) {
                                               log.info("WebSocket opened: {}", server);
                                               ws.sendText(HANDSHAKE_MSG, true);
                                               ws.request(1);
                                           }

                                           @Override
                                           public CompletionStage<?> onText(
                                           final WebSocket ws,
                                           final CharSequence data,
                                           final boolean last
                                                                           ) {
                                               messageBuffer.append(data);
                                               if (last) {
                                                   handleMessage(messageBuffer.toString());
                                                   messageBuffer.setLength(0);
                                               }
                                               ws.request(1);
                                               return null;
                                           }

                                           @Override
                                           public CompletionStage<?> onClose(
                                           final WebSocket ws,
                                           final int statusCode,
                                           final String reason
                                                                            ) {
                                               log.info("WebSocket closed: {} {}", statusCode, reason);
                                               closed[0] = true;
                                               synchronized (lock) { lock.notifyAll(); }
                                               return null;
                                           }

                                           @Override
                                           public void onError(final WebSocket ws, final Throwable error) {
                                               log.warn("WebSocket error: {}", error.getMessage());
                                               closed[0] = true;
                                               synchronized (lock) { lock.notifyAll(); }
                                           }
                                       })
                                       .join();

        synchronized (lock) {
            while (!closed[0]) { lock.wait(30_000); }
        }

        return true;
    }

    // ── Message handling ──────────────────────────────────────

    private static void handleMessage(final String raw) {
        try {
            final String   text = unpackBlitzortung(raw);
            final JsonNode data = JSON.readTree(text);

            final JsonNode latNode = data.get("lat");
            final JsonNode lonNode = data.get("lon");

            if (latNode == null || lonNode == null ||
                !latNode.isNumber() || !lonNode.isNumber()) {
                return;
            }

            publishStrike(text);

        } catch (final Exception e) {
            log.debug("Could not process message: {}",
                      raw.length() > 80 ? raw.substring(0, 80) : raw);
        }
    }

    // ── LZW Decoder ───────────────────────────────────────────
    // Direct port of reference implementation at gkbrk.com/blitzortung
    // Adapted from BlitzBridge.java by Salvi5/pink99panther

    static String unpackBlitzortung(final String raw) {
        if (raw == null || raw.isEmpty()) { return ""; }

        final Map<Integer, String> dictionary = new HashMap<>();
        char                       c          = raw.charAt(0);
        String                     f          = String.valueOf(c);
        final StringBuilder        out        = new StringBuilder(raw.length());
        out.append(c);
        int nextCode = 256;

        for (int i = 1; i < raw.length(); i++) {
            final int    code = raw.charAt(i);
            final String a;
            if (code < 256) {
                a = String.valueOf(raw.charAt(i));
            } else {
                final String entry = dictionary.get(code);
                a = (entry != null) ? entry : f + c;
            }
            out.append(a);
            c = a.charAt(0);
            dictionary.put(nextCode++, f + c);
            f = a;
        }

        return out.toString();
    }
}