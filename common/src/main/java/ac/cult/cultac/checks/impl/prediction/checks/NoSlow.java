package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.grim.grimac.api.config.ConfigManager;

/** Only source-proven incorrect movement produces a flag. Desync policy owns no violations. */
public final class NoSlow extends Check implements PostPredictionListener {
    private double threshold;

    public NoSlow(CultPlayer player) {
        super(
                player,
                CheckInfo.builder()
                        .name("NoSlow")
                        .stableKey("cult.movement.noslow")
                        .description("Ignored a proven item-use slowdown")
                        .setback(5)
                        .build());
    }

    @Override
    public void onReload(ConfigManager config) {
        threshold = Math.max(ItemUseTickResult.NUMERIC_EPSILON, config.getDoubleElse("NoSlow.threshold", 0.001));
    }

    public double threshold() {
        return threshold;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!player.usesModernJavaItemUse()
                || complete.isTeleport()
                || complete.isExempt()
                || player.gamemode == GameMode.SPECTATOR
                || player.compensatedEntities.getSelf().isDead) return;
        var result = complete.getPredictionResult();
        ItemUseTickResult best = null;
        double bestSlowed = Double.POSITIVE_INFINITY;
        // Realities already satisfy the other movement checks. Never choose a
        // horizontally convenient candidate with an illegal vertical path.
        for (var reality : result.getRealities()) {
            var evidence = reality.getItemUseResult();
            if (evidence == null) {
                result.setItemUseResult(null);
                return;
            }
            bestSlowed = Math.min(bestSlowed, evidence.slowedOffset());
            if (best == null || evidence.legalOffset() < best.legalOffset()) best = evidence;
        }
        if (best == null) return;
        best = new ItemUseTickResult(best.state(), best.legalOffset(), bestSlowed);
        result.setItemUseResult(best);
        if (best.deterministic(threshold)) {
            if (flag("offset=" + best.legalOffset() + ",tick=" + best.state().tick() + ",proof="
                            + best.state().proof())
                    && setbackIfAboveSetbackVL()) clearFlaggedUse();
        } else if (best.state().required() && best.slowedOffset() <= threshold) reward();
    }

    private void clearFlaggedUse() {
        ClientItemUseState.ActiveUse use = player.clientItemUse.activeUse();
        if (use != null && player.platformPlayer != null)
            player.platformPlayer.clearActiveItem(use.hand(), use.itemKey());
    }
}
