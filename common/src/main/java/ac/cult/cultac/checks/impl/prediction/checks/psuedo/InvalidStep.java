package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class InvalidStep extends Check implements PostPredictionListener {
    public InvalidStep(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("InvalidStep")
                        .description("Stepped up a ledge the normal game cannot step")
                        .build());
    }
}
