package ac.grim.grimac.checks.impl.prediction.checks.psuedo;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

public class NoFallPseudo extends Check implements PostPredictionListener {
    public NoFallPseudo(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder()
            .name("GroundSpoof")
            .stableKey("grim.groundspoof.fake")
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
