package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

//@CheckData(name = "Angle")
public class Angle extends Check implements PostPredictionListener {
    public Angle(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("Angle").build()); }
}
