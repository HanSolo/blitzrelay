package eu.hansolo.blitzrelay;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class BlitzRelay {
    private static final Logger           LOGGER             = LoggerFactory.getLogger(BlitzRelay.class);
    private static       Mqtt3AsyncClient mqttClient;
    private static final AtomicBoolean    mqttConnected      = new AtomicBoolean(false);
    private static final AtomicLong       strikeCount        = new AtomicLong(0);
    private static       int              currentServerIndex = 0;


    public static void main(final String[] args) throws Exception {
        LOGGER.info("BlitzRelay starting…");
        LOGGER.info("MQTT: {}:{}/{}", Constants.MQTT_HOST, Constants.MQTT_PORT, Constants.MQTT_TOPIC);

        connectMQTT();

        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> LOGGER.info("Strikes published in last 60s: {}", strikeCount.getAndSet(0)), 60, 60, TimeUnit.SECONDS);

        connectWithRetry();
    }


    private static void connectMQTT() {
        mqttClient = MqttClient.builder()
                               .useMqttVersion3()
                               .identifier("blitzrelay-" + UUID.randomUUID().toString().substring(0, 8))
                               .serverHost(Constants.MQTT_HOST)
                               .serverPort(Constants.MQTT_PORT)
                               .buildAsync();

        mqttClient.connect()
                  .whenComplete((connAck, throwable) -> {
                      if (throwable != null) {
                          LOGGER.warn("MQTT connect failed: {}", throwable.getMessage());
                          Executors.newSingleThreadScheduledExecutor().schedule(BlitzRelay::connectMQTT, 5, TimeUnit.SECONDS);
                      } else {
                          LOGGER.info("MQTT connected to {}:{}", Constants.MQTT_HOST, Constants.MQTT_PORT);
                          mqttConnected.set(true);
                      }
                  });
    }

    private static void connectWithRetry() throws InterruptedException {
        long backoffMs = Constants.INITIAL_BACKOFF_MS;

        while (true) {
            final String server = Constants.WS_SERVERS[currentServerIndex % Constants.WS_SERVERS.length];
            LOGGER.info("Connecting to Blitzortung: {}", server);

            try {
                final Object lock          = new Object();
                final AtomicBoolean closed = new AtomicBoolean(false);

                final WebSocketClient ws = new WebSocketClient(URI.create(server)) {

                    @Override public void onOpen(final ServerHandshake handshake) {
                        LOGGER.info("WebSocket opened: {}", server);
                        send("{\"a\": 111}");
                    }

                    @Override public void onMessage(final String message) {
                        // Should not be called, we override onMessage(ByteBuffer)
                        // but just in case, handle it too
                        handleRawBytes(message.getBytes(StandardCharsets.ISO_8859_1));
                    }

                    @Override public void onMessage(final java.nio.ByteBuffer bytes) {
                        // Raw bytes, no UTF-8 decoding, exactly what we need
                        final byte[] arr = new byte[bytes.remaining()];
                        bytes.get(arr);
                        handleRawBytes(arr);
                    }

                    @Override public void onClose(final int code, final String reason, final boolean remote) {
                        LOGGER.info("WebSocket closed: {} {}", code, reason);
                        closed.set(true);
                        synchronized (lock) { lock.notifyAll(); }
                    }

                    @Override public void onError(final Exception e) {
                        LOGGER.warn("WebSocket error: {}", e.getMessage());
                        closed.set(true);
                        synchronized (lock) { lock.notifyAll(); }
                    }
                };

                ws.connect();

                // Wait until closed
                synchronized (lock) {
                    while (!closed.get()) { lock.wait(30_000); }
                }

                backoffMs = Constants.INITIAL_BACKOFF_MS;

            } catch (final Exception e) {
                LOGGER.warn("Connection error: {}", e.getMessage());
            }

            currentServerIndex++;
            LOGGER.info("Reconnecting in {}ms…", backoffMs);
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, Constants.MAX_BACKOFF_MS);
        }
    }

    private static void handleRawBytes(final byte[] bytes) {
        try {
            final String decoded = Helper.lzwDecode(bytes);
            if (decoded.startsWith("{")) {
                processJson(decoded);
            }
        } catch (final Exception e) {
            LOGGER.debug("Decode error: {}", e.getMessage());
        }
    }

    private static void processJson(final String json) {
        if (json.contains("\"lat\"") && json.contains("\"lon\"") && json.contains("\"time\"")) {
            publishStrike(json);
        } else {
            LOGGER.debug("JSON filtered out — missing lat/lon/time: {}", json.substring(0, Math.min(80, json.length())));
        }
    }

    private static void publishStrike(final String json) {
        if (!mqttConnected.get()) { return; }

        mqttClient.publishWith()
                  .topic(Constants.MQTT_TOPIC)
                  .payload(json.getBytes(StandardCharsets.UTF_8))
                  .send()
                  .whenComplete((publish, throwable) -> {
                      if (throwable != null) {
                          LOGGER.warn("MQTT publish failed: {}", throwable.getMessage());
                          mqttConnected.set(false);
                          connectMQTT();
                      } else {
                          strikeCount.incrementAndGet();
                      }
                  });
    }
}