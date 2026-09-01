package ac.grim.grimac.checks.impl.prediction.checks.psuedo;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

public class RiptideOffset extends Check implements PostPredictionListener {
    public RiptideOffset(GrimPlayer playerData) {
        super(playerData, CheckInfo.builder().name("RiptideOffset").build());
    }
}
