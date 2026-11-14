package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

/**
 * The cloud service's findings on this player, one violation each, so the punishment groups that select {@code Cloud}
 * act on them like on any check. The service judges windows of frames after they happen, so a finding arrives seconds
 * after its evidence: this check never sets back or cancels. Its flags are never streamed back to the service.
 */
@CheckData(name = "Cloud", stableKey = "cult.cloud.finding", description = "Flagged by Gladiator Cloud")
public class Cloud extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("check={str}, confidence={f64}, detail={str}");

    /** The most findings that flag per player and minute. */
    static final int MAX_PER_MINUTE = 600;

    private static final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);

    private double minConfidence;
    private long minuteStart;
    private int thisMinute;

    public Cloud(CultPlayer player) {
        super(player);
    }

    /**
     * A finding the service sent, on the player's packet thread: it flags when its confidence is within 0..1 and at
     * least the configured minimum, at most {@link #MAX_PER_MINUTE} a minute.
     */
    public void finding(@NotNull String checkId, double confidence, @NotNull String detail) {
        if (!(confidence >= 0 && confidence <= 1) || confidence < minConfidence || !counted()) return;
        flag(V.write(verbose()).str(checkId).f64(confidence).str(detail));
    }

    /** Whether one more finding fits this minute; it is counted, when true. */
    private boolean counted() {
        long now = System.nanoTime();
        if (thisMinute == 0 || now - minuteStart >= MINUTE_NANOS) {
            minuteStart = now;
            thisMinute = 0;
        }
        if (thisMinute >= MAX_PER_MINUTE) return false;
        thisMinute++;
        return true;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minConfidence = config.getDoubleElse(getConfigName() + ".min-confidence", 0);
    }
}
