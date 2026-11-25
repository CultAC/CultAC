package ac.cult.cultac.checks.impl.prediction.checks.psuedo;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

public class OmniSprint extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("OmniSprint")
            .stableKey("grim.legacy.omnisprint")
            .description("Sprinted sideways or backward")
            .build();

    public OmniSprint(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }
}
