package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

//@CheckData(name="VerticalOffset")
public class VerticalOffset extends Check implements PostPredictionListener {
    public VerticalOffset(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("VerticalOffset").build()); }
}
