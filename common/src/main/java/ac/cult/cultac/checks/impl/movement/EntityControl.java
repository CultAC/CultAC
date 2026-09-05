package ac.cult.cultac.checks.impl.movement;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.DeadCheck;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.player.CultPlayer;

//@CheckData(name = "Entity control", configName = "EntityControl")
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Registered listener is a deliberate no-op.")
public class EntityControl extends Check implements PostPredictionListener {
    public EntityControl(CultPlayer cultPlayer) { super(cultPlayer, CheckInfo.builder().name("EntityControl").configName("EntityControl").build()); }

    public void rewardPlayer() {
        reward();
    }
}
