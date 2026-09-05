package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class NoFallPseudo extends Check implements PostPredictionListener {
    public NoFallPseudo(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder()
            .name("GroundSpoof")
            .stableKey("cult.groundspoof.fake")
            .description("Claimed to be on ground when predicted otherwise")
            .setback(10)
            .decay(0.01)
            .build()); }

    @Override
    public boolean flag(String verbose) {
        if (player.inVehicle()) return false;
        return super.flag(verbose);
    }
}
