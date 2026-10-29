package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class Strafe extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("Strafe")
            .description("Impossible mid-air movement")
            .build();

    public Strafe(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }
}
