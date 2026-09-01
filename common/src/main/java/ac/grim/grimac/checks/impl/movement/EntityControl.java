package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.DeadCheck;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;

//@CheckData(name = "Entity control", configName = "EntityControl")
@DeadCheck(reason = DeadCheck.Reason.DEAD_BY_CONSTRUCTION, detail = "Registered listener is a deliberate no-op.")
public class EntityControl extends Check implements PostPredictionListener {
    public EntityControl(GrimPlayer grimPlayer) { super(grimPlayer, CheckInfo.builder().name("EntityControl").configName("EntityControl").build()); }

    public void rewardPlayer() {
        reward();
    }
}
