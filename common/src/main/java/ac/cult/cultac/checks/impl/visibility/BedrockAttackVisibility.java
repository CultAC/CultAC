package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/**
 * Whether a Bedrock client attacked through blocks. The attack carries the point where its pick entered the target, so
 * it is judged at that point: no block may lie between an eye it attacked from and that point. The reach checks hand
 * the attack over as it arrives.
 */
public abstract class BedrockAttackVisibility extends BedrockVisibilityCheck {
    protected BedrockAttackVisibility(CultPlayer player) {
        super(player);
    }

    /**
     * Judges an attack whose pick entered the target at {@code hit}, whose floats may lie {@code precision} from it,
     * with the verbose {@code detail}; true when the attack must be dropped.
     */
    public boolean dropsAttack(Vec3 hit, double precision, String detail) {
        if (hit == null || !judged()) return false;
        if (!hidden(List.of(lastLeg()), around(hit, precision), (BlockPos) null)) return false;
        return flag(detail) && shouldModifyPackets();
    }
}
