package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class BoatPseudo extends Check implements PostPredictionListener {
    public BoatPseudo(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("BoatOffset")
                        .description("Moved a boat in a way the normal game does not allow")
                        .build());
    }
}
