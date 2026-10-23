package ac.cult.cultac.checks.impl.cloud;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import org.jetbrains.annotations.NotNull;

/**
 * The cloud service's findings on this player, one violation each, so the punishment groups that select {@code Cloud}
 * act on them like on any check. The service judges windows of frames after they happen, so a finding arrives seconds
 * after its evidence: this check never sets back or cancels. Its flags are never streamed back to the service.
 */
@CheckData(name = "Cloud", stableKey = "cult.cloud.finding", description = "Flagged by Gladiator Cloud")
public class Cloud extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("check={str}, confidence={f64}, detail={str}");

    private double minConfidence;

    public Cloud(CultPlayer player) {
        super(player);
    }

    /** A finding the service sent, on the player's packet thread. */
    public void finding(@NotNull String checkId, double confidence, @NotNull String detail) {
        if (confidence < minConfidence) return;
        flag(V.write(verbose()).str(checkId).f64(confidence).str(detail));
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        minConfidence = config.getDoubleElse(getConfigName() + ".min-confidence", 0);
    }
}
