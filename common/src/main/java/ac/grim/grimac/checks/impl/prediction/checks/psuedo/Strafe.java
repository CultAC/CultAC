package ac.grim.grimac.checks.impl.prediction.checks.psuedo;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

//@CheckData(name = "Strafe")
public class Strafe extends Check implements PostPredictionListener {
    public Strafe(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("Strafe").build()); }
}
