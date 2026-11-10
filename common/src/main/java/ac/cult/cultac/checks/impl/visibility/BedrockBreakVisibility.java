package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockEye;
import ac.cult.cultac.utils.visibility.BedrockSight;
import java.util.List;

/**
 * BreakVisibility for a Bedrock client's block actions. An action names the block it breaks and a face but no point on
 * it, so it is judged on the whole block, with the block itself never in the way. A tick's actions are judged from the
 * path between its last two committed positions and from the tick's own move toward the position its input claims; an
 * input whose move is then rejected forwards none of its actions. Each action is judged against the world its earlier
 * actions in the same input left.
 */
@CheckData(
        name = "bBreakVisibility",
        stableKey = "cult.bedrock.break.visibility",
        description = "Broke a block through blocks on Bedrock Edition",
        experimental = true)
public final class BedrockBreakVisibility extends BedrockVisibilityCheck {
    public BedrockBreakVisibility(CultPlayer player) {
        super(player);
    }

    /**
     * Judges the action {@code action} on the world block {@code block}'s face {@code face} in an input whose move
     * claims the feet position {@code claimed}; true when it must be dropped.
     */
    public boolean dropsAction(String action, BlockPos block, int face, Vec3 claimed) {
        if (claimed == null || !judged()) return false;
        var next = new BedrockSight.Leg(new Vec3(player.x, player.y, player.z), claimed, BedrockEye.any(player));
        if (!hidden(List.of(lastLeg(), next), cell(block), block)) return false;
        return flag("action=" + action + ", " + block(block, face)) && shouldModifyPackets();
    }
}
