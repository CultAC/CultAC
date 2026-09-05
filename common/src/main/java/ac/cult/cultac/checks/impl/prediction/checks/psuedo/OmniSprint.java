package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

//@CheckData(name = "OmniSprint")
public class OmniSprint extends Check implements PostPredictionListener {
    public OmniSprint(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("OmniSprint").build()); }

    double buffer;
    double maxBuffer;
    double decay;

    @Override
    public void reload() {
        super.reload();
        buffer = 0;
        maxBuffer = getConfig().getDoubleElse("omnisprint-buffer-threshold", 20);
        decay = getConfig().getDoubleElse("omnisprint-decay", 0.5);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        buffer = Math.max(0, buffer - decay);
    }

    @Override
    public boolean flag(String detail) {
        buffer = Math.min(buffer + 1, maxBuffer);
        // only alert if we're at the max buffer
        if (buffer == maxBuffer) { player.getSetbackTeleportUtil().executeForceResync("omnisprint"); }
        return false;
    }
}
