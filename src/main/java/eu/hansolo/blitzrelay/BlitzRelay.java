package eu.hansolo.blitzrelay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


public class BlitzRelay {
    private static final Logger           log           = LoggerFactory.getLogger(BlitzRelay.class);
    private static       Mqtt3AsyncClient mqttClient;
    private static final AtomicBoolean    mqttConnected = new AtomicBoolean(false);
    private static final ObjectMapper     JSON          = new ObjectMapper();
    private static final AtomicLong       strikeCount   = new AtomicLong(0);
    private static final HttpClient       httpClient    = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private static       int              serverIndex   = 0;


    public static void main(final String[] args) throws Exception {
        log.info("BlitzRelay starting…");
        log.info("MQTT: {}:{} topic: {}", Constants.MQTT_HOST, Constants.MQTT_PORT, Constants.MQTT_TOPIC);

        connectMQTT();

        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> log.info("Strikes published in last 60s: {}", strikeCount.getAndSet(0)), 60, 60, TimeUnit.SECONDS);

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
                          log.warn("MQTT connect failed: {}", throwable.getMessage());
                          Executors.newSingleThreadScheduledExecutor().schedule(BlitzRelay::connectMQTT, 5, TimeUnit.SECONDS);
                      } else {
                          log.info("MQTT connected to {}:{}", Constants.MQTT_HOST, Constants.MQTT_PORT);
                          mqttConnected.set(true);
                      }
                  });
    }

    private static void connectWithRetry() throws InterruptedException {
        long backoffMs = Constants.INITIAL_BACKOFF_MS;

        while (true) {
            final String server = Constants.WS_SERVERS.get(serverIndex % Constants.WS_SERVERS.size());
            log.info("Connecting to Blitzortung: {}", server);

            try {
                connectBlitzortung(server);
                backoffMs = Constants.INITIAL_BACKOFF_MS;
            } catch (final Exception e) {
                log.warn("Connection error: {}", e.getMessage());
            }

            serverIndex++;
            log.info("Reconnecting in {}ms…", backoffMs);
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, Constants.MAX_BACKOFF_MS);
        }
    }

    private static boolean connectBlitzortung(final String server) throws Exception {
        final AtomicBoolean sessionActive = new AtomicBoolean(true);
        final Object        lock          = new Object();

        httpClient.newWebSocketBuilder()
                  .connectTimeout(Duration.ofSeconds(20))
                  .buildAsync(URI.create(server), new WebSocket.Listener() {
                      private final StringBuilder buffer = new StringBuilder();

                      @Override public void onOpen(final WebSocket ws) {
                                               log.info("WebSocket opened: {}", server);
                                               ws.sendText(Constants.HANDSHAKE_MSG, true);
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

                      @Override public CompletionStage<?> onClose(final WebSocket ws, final int statusCode, final String reason) {
                                               log.info("WebSocket closed: {} {}", statusCode, reason);
                                               sessionActive.set(false);
                                               synchronized (lock) { lock.notifyAll(); }
                                               return null;
                                           }

                      @Override public void onError(final WebSocket ws, final Throwable error) {
                                               log.warn("WebSocket error: {}", error.getMessage());
                                               sessionActive.set(false);
                                               synchronized (lock) { lock.notifyAll(); }
                                           }
                  })
                  .join();

        synchronized (lock) {
            while (sessionActive.get()) { lock.wait(30_000); }
        }
        return true;
    }

    private static void handleMessage(final String raw) {
        try {
            final String   text    = Helper.decode(raw);
            final JsonNode data    = JSON.readTree(text);

            final JsonNode latNode = data.get("lat");
            final JsonNode lonNode = data.get("lon");

            if (latNode == null || lonNode == null || !latNode.isNumber() || !lonNode.isNumber()) { return; }
            publishStrike(text);
        } catch (final Exception e) {
            log.debug("Could not process message: {}", raw.length() > 80 ? raw.substring(0, 80) : raw);
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
                          log.warn("MQTT publish failed: {}", throwable.getMessage());
                          mqttConnected.set(false);
                          connectMQTT();
                      } else {
                          strikeCount.incrementAndGet();
                      }
                  });
    }
}