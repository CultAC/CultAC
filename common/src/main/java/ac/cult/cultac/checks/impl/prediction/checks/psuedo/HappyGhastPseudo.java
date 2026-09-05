package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class HappyGhastPseudo extends Check implements PostPredictionListener {
    public HappyGhastPseudo(CultPlayer player) {
        super(player, CheckInfo.builder().name("HappyGhastOffset").build());
    }
}
