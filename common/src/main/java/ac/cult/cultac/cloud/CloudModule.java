package ac.cult.cultac.cloud;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.List;
import java.util.UUID;

/**
 * The anticheat's only way into the cloud upload. Until a config sets {@code cloud.url} or {@code cloud.api-key},
 * every call returns at once and no other cloud class is loaded.
 */
public final class CloudModule {
    /** A config has named the upload: calls reach {@link CloudService} from here on. */
    private static volatile boolean loaded;
    /** A newer runtime generation owns the upload; this one never loads it. */
    private static volatile boolean superseded;

    private static volatile Runnable turnedOn = () -> {};
    private static volatile Runnable turnedOff = () -> {};

    private CloudModule() {}

    /** Applies the config's {@code cloud} section; at startup and after each reload. */
    public static void configReloaded() {
        if (superseded) return;
        if (!loaded) {
            ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
            if (blank(config.getStringElse("cloud.url", "")) && blank(config.getStringElse("cloud.api-key", "")))
                return;
            loaded = true;
        }
        CloudService.configReloaded();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** {@code player} is tracked. Any thread. */
    public static void playerRegistered(CultPlayer player) {
        if (loaded) CloudService.playerRegistered(player);
    }

    /** {@code player} is no longer tracked. */
    public static void playerRemoved(UUID player) {
        if (loaded) CloudService.playerRemoved(player);
    }

    /** {@code player}'s connection moves to a newer runtime; on the connection's packet owner. */
    public static void released(CultPlayer player) {
        if (loaded) CloudCapture.release(player);
    }

    /** Plugin disable. */
    public static void shutdown() {
        if (loaded) CloudService.shutdown();
    }

    /** A newer runtime generation took over; blocks for at most {@code detachMillis}. */
    public static void superseded(long detachMillis) {
        superseded = true;
        if (loaded) CloudService.superseded(detachMillis);
    }

    /** The upload's status, one line each. */
    public static List<String> status() {
        return loaded ? CloudService.status() : List.of("Cloud upload off");
    }

    /**
     * The platform's cloud glue: {@code on} runs, in place of any earlier hook, each time the upload turns on, before
     * any capture installs, and {@code off} each time it turns off. Neither runs while the upload is not configured.
     */
    public static void platformHooks(Runnable on, Runnable off) {
        turnedOn = on;
        turnedOff = off;
    }

    static Runnable turnedOn() {
        return turnedOn;
    }

    static Runnable turnedOff() {
        return turnedOff;
    }
}
