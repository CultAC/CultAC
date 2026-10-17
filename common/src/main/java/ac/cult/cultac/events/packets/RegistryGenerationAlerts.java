package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Reports registry data CultAC cannot model and alerts staff once per occurrence per window. */
public final class RegistryGenerationAlerts {
    static final long WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
    static final int MAX_OCCURRENCES = 64;
    private static final String SUPPRESSED =
            " Repeats are suppressed for " + TimeUnit.MILLISECONDS.toMinutes(WINDOW_MILLIS) + " minutes.";
    private static final Map<String, Long> lastAlerts = new HashMap<>();

    private RegistryGenerationAlerts() {}

    static void report(CultPlayer player, Throwable failure) {
        CultNetworkManager.reportFailure("registry generation for " + player.getName(), failure);
        String occurrence = failure.getClass().getName() + ": " + failure.getMessage();
        if (!admit(occurrence, System.currentTimeMillis())) return;
        alert("CultAC could not model the registry data sent to " + player.getName() + " (" + occurrence
                + "). Players configured by that server use model defaults until it reconfigures them." + SUPPRESSED);
    }

    /** Logs once per backend and dimension type, without a stack trace, and tells staff that checks are paused. */
    public static void unresolvedDimension(CultPlayer player, int dimensionType, Throwable failure) {
        String backend = player.user.getCultConnection().platform() == null
                ? null
                : player.user.getCultConnection().platform().backendName();
        String server = backend == null ? "this server" : backend;
        if (!admit("dimension " + server + " " + dimensionType, System.currentTimeMillis())) return;
        LogUtil.warn("CultAC paused checks for " + player.getName() + " on " + server + ": dimension type "
                + dimensionType + " is unknown (" + failure.getMessage() + ")");
        alert("CultAC paused checks for " + player.getName() + " on " + server
                + " because the server sent registry data CultAC cannot model (dimension type " + dimensionType
                + "). Checks resume when that player reaches a level CultAC can model." + SUPPRESSED);
    }

    private static void alert(String message) {
        CultAPI.INSTANCE.getAlertManager().sendAlert(Component.text(message, NamedTextColor.RED), null);
    }

    /**
     * True when {@code occurrence} has not alerted within the window ending at {@code now}. At capacity, expired
     * occurrences are dropped; live ones keep their suppression and a new occurrence is not admitted.
     */
    static synchronized boolean admit(String occurrence, long now) {
        Long previous = lastAlerts.get(occurrence);
        if (previous != null && now - previous < WINDOW_MILLIS) return false;
        if (previous == null && lastAlerts.size() >= MAX_OCCURRENCES) {
            lastAlerts.values().removeIf(alerted -> now - alerted >= WINDOW_MILLIS);
            if (lastAlerts.size() >= MAX_OCCURRENCES) return false;
        }
        lastAlerts.put(occurrence, now);
        return true;
    }
}
