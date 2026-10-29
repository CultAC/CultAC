package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import java.util.function.Supplier;

public class OmniSprint extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("OmniSprint")
            .description("Sprinted sideways or backward")
            .build();

    public OmniSprint(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }

    double buffer;
    double maxBuffer;
    double decay;

    @Override
    public void reload() {
        super.reload();
        buffer = 0;
        maxBuffer = getConfig().getDoubleElse("cult.checks.omni-sprint.buffer-threshold", 20);
        decay = getConfig().getDoubleElse("cult.checks.omni-sprint.buffer-decay", 0.5);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        buffer = Math.max(0, buffer - decay);
    }

    @Override
    public boolean flag(Supplier<String> detail) {
        buffer = Math.min(buffer + 1, maxBuffer);
        if (buffer == maxBuffer) {
            player.getSetbackTeleportUtil().executeForceResync("omnisprint");
        }
        return false;
    }
}
