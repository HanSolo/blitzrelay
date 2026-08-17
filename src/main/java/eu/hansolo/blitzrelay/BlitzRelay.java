package eu.hansolo.blitzrelay;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

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
import java.util.logging.Level;
import java.util.logging.Logger;


public class BlitzRelay {
    private static final Logger           log                = Logger.getLogger(BlitzRelay.class.getName());
    private static       Mqtt3AsyncClient mqttClient;
    private static final AtomicBoolean    mqttConnected      = new AtomicBoolean(false);
    private static final AtomicLong       strikeCount        = new AtomicLong(0);
    private static       int              currentServerIndex = 0;


    public static void main(final String[] args) throws Exception {
        log.info("BlitzRelay starting…");
        log.info("MQTT: " + Constants.MQTT_HOST + ":" + Constants.MQTT_PORT + " topic: " + Constants.MQTT_TOPIC);

        connectMQTT();

        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> log.info("Strikes published in last 60s: " + strikeCount.getAndSet(0)), 60, 60, TimeUnit.SECONDS);

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
                          log.severe("MQTT connect failed: " + throwable.getMessage());
                          Executors.newSingleThreadScheduledExecutor().schedule(BlitzRelay::connectMQTT, 5, TimeUnit.SECONDS);
                      } else {
                          log.info("MQTT connected to " + Constants.MQTT_HOST + ":" + Constants.MQTT_PORT);
                          mqttConnected.set(true);
                      }
                  });
    }

    private static void connectWithRetry() throws InterruptedException {
        long backoffMs = Constants.INITIAL_BACKOFF_MS;

        while (true) {
            final String server = Constants.WS_SERVERS[currentServerIndex % Constants.WS_SERVERS.length];
            log.info("Connecting to Blitzortung: " + server);

            try {
                connectBlitzortung(server);
                backoffMs = Constants.INITIAL_BACKOFF_MS;
            } catch (final Exception e) {
                log.log(Level.WARNING, "Connection error", e);
            }

            currentServerIndex++;
            log.info("Reconnecting in " + backoffMs + "ms…");
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
                          log.info("WebSocket opened: " + serverUrl);
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
                              log.warning("Binary decode error: " + e.getMessage());
                          }
                          ws.request(1);
                          return null;
                      }

                      @Override public CompletionStage<?> onClose(final WebSocket ws, final int statusCode, final String reason) {
                          log.info("WebSocket closed: " + statusCode + " " + reason);
                          sessionActive.set(false);
                          synchronized (lock) { lock.notifyAll(); }
                          return null;
                      }

                      @Override public void onError(final WebSocket ws, final Throwable error) {
                          log.warning("WebSocket error: " + error.getMessage());
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
            final String json = raw.trim().startsWith("{") ? raw.trim() : new String(Helper.lzwDecode(raw.getBytes(StandardCharsets.ISO_8859_1)), StandardCharsets.UTF_8).trim();
            if (json.startsWith("{")) { processJson(json); }
        } catch (final Exception e) {
            log.fine("Decode error: " + e.getMessage());
        }
    }

    private static void processJson(final String json) {
        if (json.contains("\"lat\"") && json.contains("\"lon\"") && json.contains("\"time\"")) {
            publishStrike(json);
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
                          log.warning("MQTT publish failed: " + throwable.getMessage());
                          mqttConnected.set(false);
                          connectMQTT();
                      } else {
                          strikeCount.incrementAndGet();
                      }
                  });
    }
}