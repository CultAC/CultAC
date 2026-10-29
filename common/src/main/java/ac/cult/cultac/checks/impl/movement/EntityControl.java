package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Registered listener is a deliberate no-op.")
public class EntityControl extends Check implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("EntityControl")
            .description("Controlled a ridden mob in an impossible way")
            .configName("EntityControl")
            .build();

    public EntityControl(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
    }

    public void rewardPlayer() {
        reward();
    }
}
