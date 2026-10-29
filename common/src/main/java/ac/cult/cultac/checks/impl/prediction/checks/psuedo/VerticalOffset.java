package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class VerticalOffset extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("VerticalOffset")
            .description("Impossible up or down movement")
            .build();

    public VerticalOffset(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }
}
