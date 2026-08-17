package eu.hansolo.blitzrelay;

import java.util.List;


public final class Constants {

    public static final List<String> WS_SERVERS         = List.of("wss://ws1.blitzortung.org", "wss://ws2.blitzortung.org", "wss://ws7.blitzortung.org", "wss://ws8.blitzortung.org");
    public static final String       MQTT_HOST          = System.getenv().getOrDefault("MQTT_HOST",  "host.docker.internal");
    public static final int          MQTT_PORT          = Integer.parseInt(System.getenv().getOrDefault("MQTT_PORT",  "1883"));
    public static final String       MQTT_TOPIC         = System.getenv().getOrDefault("MQTT_TOPIC", "lightning/strikes");
    public static final String       HANDSHAKE_MSG      = "{\"a\":111}";
    public static final long         INITIAL_BACKOFF_MS = 2_000;
    public static final long         MAX_BACKOFF_MS     = 60_000;
}
