package eu.hansolo.blitzrelay;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
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
                connectBlitzortung(server);
                backoffMs = Constants.INITIAL_BACKOFF_MS;
            } catch (final Exception e) {
                LOGGER.warn("Connection error {}", e);
            }

            currentServerIndex++;
            LOGGER.info("Reconnecting in {} ms...", backoffMs);
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, Constants.MAX_BACKOFF_MS);
        }
    }

    private static void connectBlitzortung(final String serverUrl) throws Exception {
        final AtomicBoolean sessionActive = new AtomicBoolean(true);
        final Object        lock          = new Object();

        HttpClient.newHttpClient()
                  .newWebSocketBuilder()
                  .buildAsync(URI.create(serverUrl), new WebSocket.Listener() {

                      private final StringBuilder buffer = new StringBuilder();

                      @Override public void onOpen(final WebSocket ws) {
                          LOGGER.info("WebSocket opened: {}", serverUrl);
                          ws.sendText("{\"a\": 111}", true);
                          ws.request(1);
                      }

                      @Override public CompletionStage<?> onText(final WebSocket ws, final CharSequence data, final boolean last) {
                          buffer.append(data);
                          if (last) {
                              handleMessage(buffer.toString());
                              buffer.setLength(0);
                          }
                          ws.request(1);
                          return null;
                      }

                      @Override public CompletionStage<?> onBinary(final WebSocket ws, final ByteBuffer data, final boolean last) {
                          final byte[] bytes = new byte[data.remaining()];
                          data.get(bytes);
                          try {
                              handleMessage(new String(Helper.lzwDecode(bytes), StandardCharsets.UTF_8));
                          } catch (final Exception e) {
                              LOGGER.warn("Binary decode error: {}", e.getMessage());
                          }
                          ws.request(1);
                          return null;
                      }

                      @Override public CompletionStage<?> onClose(final WebSocket ws, final int statusCode, final String reason) {
                          LOGGER.info("WebSocket closed: {} {}", statusCode, reason);
                          sessionActive.set(false);
                          synchronized (lock) { lock.notifyAll(); }
                          return null;
                      }

                      @Override public void onError(final WebSocket ws, final Throwable error) {
                          LOGGER.warn("WebSocket error: {}", error.getMessage());
                          sessionActive.set(false);
                          synchronized (lock) { lock.notifyAll(); }
                      }
                  })
                  .get(10, TimeUnit.SECONDS);

        synchronized (lock) {
            while (sessionActive.get()) { lock.wait(30_000); }
        }
    }


    private static void handleMessage(final String raw) {
        if (raw == null || raw.isBlank()) { return; }
        try {
            final String json = raw.trim().startsWith("{") ? raw.trim() : Helper.lzwDecode(raw).trim();
            if (json.startsWith("{")) { processJson(json); }
        } catch (final Exception e) {
            LOGGER.debug("Decode error: {}", e.getMessage());
        }
    }

    private static void processJson(final String json) {
        LOGGER.debug("Processing JSON: {}", json.length() > 100 ? json.substring(0, 100) : json);
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