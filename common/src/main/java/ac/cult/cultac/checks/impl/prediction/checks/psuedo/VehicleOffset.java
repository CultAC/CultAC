package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class VehicleOffset extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("VehicleOffset")
            .description("Impossible vehicle movement")
            .build();

    public VehicleOffset(CultPlayer player) {
        super(player, INFO);
    }
}
