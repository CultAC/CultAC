package ac.grim.grimac.checks.impl.prediction.checks.psuedo;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

//@CheckData(name = "Angle")
public class Angle extends Check implements PostPredictionListener {
    public Angle(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("Angle").build()); }
}
