package ac.grim.grimac.checks.impl.prediction.checks.psuedo;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

public class HappyGhastPseudo extends Check implements PostPredictionListener {
    public HappyGhastPseudo(GrimPlayer player) {
        super(player, CheckInfo.builder().name("HappyGhastOffset").build());
    }
}
