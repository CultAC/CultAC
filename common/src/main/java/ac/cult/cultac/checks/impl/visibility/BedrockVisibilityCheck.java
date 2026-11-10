package ac.cult.cultac.checks.impl.visibility;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.visibility.BedrockEye;
import ac.cult.cultac.utils.visibility.BedrockSight;
import java.util.List;

/**
 * A visibility check for a Bedrock client's picks, which the Geyser bridge hands over directly: they never arrive as
 * Java packets. A pick is judged from the eye path between the client's tick positions (BedrockSight) and the blocks
 * certain to stop it, not from Java's single eye and outline shapes.
 */
abstract class BedrockVisibilityCheck extends Check implements CheckListener {
    protected BedrockVisibilityCheck(CultPlayer player) {
        super(player);
    }

    /**
     * Whether the client's picks are judged: not while mounted, whose eye the path does not model, nor before its
     * first tick or as a spectator.
     */
    protected boolean judged() {
        return !player.isDisabled()
                && player.isBedrockMovement()
                && player.packetStateData.localPlayerTickedSinceSpawn
                && player.gamemode != GameMode.SPECTATOR
                && player.compensatedEntities.getSelf().getRiding() == null;
    }

    /** The leg of the eye path between the client's last two tick positions. */
    protected BedrockSight.Leg lastLeg() {
        return new BedrockSight.Leg(
                new Vec3(player.lastX, player.lastY, player.lastZ),
                new Vec3(player.x, player.y, player.z),
                BedrockEye.ofOrAny(player));
    }

    protected boolean hidden(List<BedrockSight.Leg> legs, SimpleCollisionBox target, BlockPos excluded) {
        return BedrockSight.hidden(player, legs, target, excluded);
    }

    protected static SimpleCollisionBox around(Vec3 point, double precision) {
        return new SimpleCollisionBox(
                point.x - precision,
                point.y - precision,
                point.z - precision,
                point.x + precision,
                point.y + precision,
                point.z + precision);
    }

    protected static SimpleCollisionBox cell(BlockPos pos) {
        return new SimpleCollisionBox(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    protected String block(BlockPos position, int face) {
        int state = player.compensatedWorld.getBlockStateIdAt(position);
        return "block=" + DataTables.defaults().registry().block(state).key() + ", pos=" + position.getX() + ", "
                + position.getY() + ", " + position.getZ() + ", face=" + face;
    }
}
