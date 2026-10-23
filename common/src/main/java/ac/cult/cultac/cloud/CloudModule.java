package ac.cult.cultac.cloud;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.cloud.diag.PredictionDiagnostics;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The anticheat's only way into the cloud upload. Until {@code cloud.url} or {@code cloud.api-key}, or
 * {@code CULTAC_CLOUD_URL} or {@code CULTAC_CLOUD_API_KEY}, is set, every call returns at once and no other cloud
 * class is loaded.
 */
public final class CloudModule {
    /** A config has named the upload: calls reach {@link CloudService} from here on. */
    private static volatile boolean loaded;
    /** A newer runtime generation took over; an upload this one has not loaded stays unloaded. */
    private static volatile boolean superseded;

    private static volatile Runnable turnedOn = () -> {};
    private static volatile Runnable turnedOff = () -> {};

    private CloudModule() {}

    /** Applies the config's {@code cloud} section; at startup and after each reload. */
    public static void configReloaded() {
        if (!loaded) {
            if (superseded) return;
            ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
            if (setting(config, System::getenv, "cloud.url", "CULTAC_CLOUD_URL", "")
                            .isEmpty()
                    && apiKey(config, System::getenv).isEmpty()) return;
            CloudService.listenForTeam();
            loaded = true;
        }
        CloudService.configReloaded();
        PredictionDiagnostics.readServerFields();
    }

    /** This server's intake key: {@code CULTAC_CLOUD_API_KEY}, else {@code cloud.api-key}; empty is off. */
    static String apiKey(ConfigManager config, Function<String, String> env) {
        return setting(config, env, "cloud.api-key", "CULTAC_CLOUD_API_KEY", "");
    }

    /** The environment variable when set and not blank, else the config value when not blank, else {@code fallback}. */
    static String setting(
            ConfigManager config, Function<String, String> env, String key, String envName, String fallback) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) value = config.getStringElse(key, fallback);
        if (value == null || value.isBlank()) return fallback;
        return value.strip();
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

    /**
     * A newer runtime generation took over: the players still here keep streaming under server ids ending in
     * {@code drainSuffix}, and a reload still applies. Does not block.
     */
    public static void superseded(String drainSuffix) {
        superseded = true;
        if (loaded) CloudService.superseded(drainSuffix);
    }

    /** The upload's current settings; null until a config names the upload. Any thread. */
    public static @Nullable CloudSettings settings() {
        return loaded ? CloudService.settings() : null;
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
