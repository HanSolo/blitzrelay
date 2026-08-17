package eu.hansolo.blitzrelay;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
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
                final Object        lock   = new Object();
                final AtomicBoolean closed = new AtomicBoolean(false);

                final WebSocketClient ws = new WebSocketClient(URI.create(server)) {
                    {
                        setConnectionLostTimeout(30);
                    }

                    @Override public void onOpen(final ServerHandshake handshake) {
                        LOGGER.info("WebSocket opened: {}", server);
                        send("{\"a\": 111}");
                    }

                    @Override public void onMessage(final String message) {
                        // Java-WebSocket may decode some bytes as UTF-8 multi-byte sequences
                        // producing code points above 255. Re-encode as bytes using the
                        // raw char values to recover the original byte stream.
                        final byte[] rawBytes = new byte[message.length()];
                        for (int i = 0; i < message.length(); i++) {
                            rawBytes[i] = (byte)(message.charAt(i) & 0xFF);
                        }
                        // Now decode back to String treating each byte as a Latin-1 char
                        final String latin1 = new String(rawBytes, StandardCharsets.ISO_8859_1);
                        handleMessage(latin1);
                    }

                    @Override public void onMessage(final ByteBuffer bytes) {
                        // Binary frame, decode as UTF-8 String first
                        final byte[] arr = new byte[bytes.remaining()];
                        bytes.get(arr);
                        handleMessage(new String(arr, StandardCharsets.UTF_8));
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

    private static void handleMessage(final String raw) {
        if (raw == null || raw.isBlank()) { return; }
        try {
            final String json = raw.trim().startsWith("{") ? raw.trim() : Helper.lzwDecode(raw).trim();
            LOGGER.debug("Full decoded: {}", json);   // add this
            if (json.startsWith("{")) { processJson(json); }
        } catch (final Exception e) {
            LOGGER.debug("Decode error: {}", e.getMessage());
        }
    }

    private static void processJson(final String json) {
        LOGGER.debug("Publishing: {}", json);
        publishStrike(json);
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