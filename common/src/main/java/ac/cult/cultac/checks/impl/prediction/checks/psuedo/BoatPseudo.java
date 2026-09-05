package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

//@CheckData(name = "Boat")
public class BoatPseudo extends Check implements PostPredictionListener {
    public BoatPseudo(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("BoatOffset").build()); }
}
