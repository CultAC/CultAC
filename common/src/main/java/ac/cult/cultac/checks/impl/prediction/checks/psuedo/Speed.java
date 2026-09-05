package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

//@CheckData(name = "Speed")
public class Speed extends Check implements PostPredictionListener {
    public Speed(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("Speed").build()); }
}
