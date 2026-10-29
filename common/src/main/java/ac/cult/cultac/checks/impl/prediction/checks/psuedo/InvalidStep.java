package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class InvalidStep extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("InvalidStep")
            .description("Made an impossible step up")
            .build();

    public InvalidStep(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }
}
