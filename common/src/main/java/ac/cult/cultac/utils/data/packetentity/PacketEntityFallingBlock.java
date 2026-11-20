package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBoxPool;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A {@code FallingBlockEntity}, which the client moves by its own gravity and delta movement every tick between server
 * positions.
 */
public final class PacketEntityFallingBlock extends PacketEntity {
    // Entity#verticalCollision and onGround, which Entity#move refreshes only on a move with vertical delta.
    private boolean verticalCollision;
    private boolean onGround;

    public PacketEntityFallingBlock(CultPlayer player, int id, Vec3 position) {
        super(player, id, EntityTypeIds.FALLING_BLOCK, position.x, position.y, position.z);
    }

    /**
     * {@code FallingBlockEntity#tick} on the client: {@code applyGravity}, {@code move(MoverType.SELF)}, then
     * {@code getAirDrag}. Each possible location moves from its own position; the client-physical one decides the delta
     * movement. Entity colliders, block step-on and inside effects are not modelled.
     */
    @Override
    public void onMovement(CultPlayer player, boolean tickingReliably) {
        super.onMovement(player, tickingReliably);
        // Bedrock clients see Geyser's per-tick absolute moves, which the Geyser bridge already applies.
        if (getRiding() != null || player.isBedrockMovement()) return;
        // Entity#getGravity: FallingBlockEntity#getDefaultGravity, or none with the no_gravity flag.
        if (hasGravity) deltaMovement = deltaMovement.add(0.0D, -0.04D, 0.0D);
        Vec3 delta = deltaMovement;
        if (delta.lengthSqr() != 0.0D) {
            UnaryOperator<Vec3> step = from -> applied(delta, collide(player, from, delta));
            newPacketLocation.offset(step);
            if (oldPacketLocation != null) {
                oldPacketLocation.offset(step);
            }
        }
        if (clientPhysicalPosition != null) {
            Vec3 movement = delta.lengthSqr() == 0.0D ? delta : collide(player, clientPhysicalPosition, delta);
            clientPhysicalPosition = clientPhysicalPosition.add(applied(delta, movement));
            boolean xCollision = !CultMath.equal(delta.x, movement.x);
            boolean zCollision = !CultMath.equal(delta.z, movement.z);
            if (delta.y != 0.0D) {
                verticalCollision = delta.y != movement.y;
                onGround = verticalCollision && delta.y < 0.0D;
            }
            if (player.getClientVersion().isOlderThan(ClientVersion.V_26_2)) {
                // Entity#move zeroes each horizontally collided axis.
                deltaMovement = new Vec3(
                        xCollision ? 0.0D : deltaMovement.x, deltaMovement.y, zCollision ? 0.0D : deltaMovement.z);
            } else if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                    && (delta.y != 0.0D && verticalCollision || xCollision || zCollision)) {
                // 26.3 FallingBlockEntity#getMoveSimulationType is SERVER_AND_CLIENT, so canSimulateMovement holds on
                // the client and Entity#move restitutes; 26.2 restitutes only on the authoritative side.
                restitute(player, xCollision, zCollision, movement);
            }
            float speedFactor = BlockProperties.getEntityBlockSpeedFactor(
                    player,
                    new MainSupportingBlockData(null, onGround),
                    clientPhysicalPosition);
            deltaMovement = deltaMovement.multiply(speedFactor, 1.0D, speedFactor);
        }
        deltaMovement = deltaMovement.scale((double) 0.98F);
    }

    /**
     * 26.3 {@code Entity#restituteMovementAfterCollisions} with no entity bounciness: a collided axis stops, and a
     * landing faster than gravity on a bouncy block outside {@code #suppresses_bounce} bounces at 80% of its restitution.
     * The block under the centre stands in for {@code getOnPosLegacy}'s supporting block.
     */
    private void restitute(CultPlayer player, boolean xCollision, boolean zCollision, Vec3 movement) {
        Vec3 current = deltaMovement;
        double x = xCollision ? 0.0D : current.x;
        double z = zCollision ? 0.0D : current.z;
        double y = current.y;
        if (verticalCollision) {
            double gravity = hasGravity ? 0.04D : 0.0D;
            double restitution = 0.0D;
            if (onGround && !(-current.y <= gravity)) {
                int effect = BlockProperties.getOnPos(
                        player, new MainSupportingBlockData(null, onGround), clientPhysicalPosition);
                if (!BlockIds.is(effect, BlockIds.HONEY_BLOCK))
                    restitution = Math.max(0.0D, VelocityCandidates.blockBounceRestitution(effect) * 0.8F);
            }
            y = restitution > 0.0D
                    ? VelocityCandidates.restitutedBounceY(current.y, movement.y, gravity, (double) 0.98F, restitution)
                    : (0.0D - current.y) * restitution;
        }
        deltaMovement = new Vec3(x, y, z);
    }

    /** {@code Entity#collide} without a step height: {@code collideBoundingBox} against blocks. */
    private Vec3 collide(CultPlayer player, Vec3 from, Vec3 delta) {
        SimpleCollisionBox box = toBoundingBox(
                player, GetBoundingBox.getPacketEntityBoundingBox(player, from.x, from.y, from.z, this));
        CollisionBoxPool pool = CollisionBoxPool.current();
        int mark = pool.mark();
        try {
            List<SimpleCollisionBox> colliders = new ArrayList<>();
            // CollisionContext.of(this falling block) at its own feet.
            Collisions.getCollisionBoxes(
                    player,
                    box.copy().expandToCoordinate(delta.x, delta.y, delta.z),
                    colliders,
                    false,
                    from.y,
                    JavaCollisionState.of(player, this, 0.0D),
                    pool);
            return Collisions.collideBoundingBoxLegacy(
                    delta, box, colliders, Collisions.getAxisStepOrder(delta));
        } finally {
            pool.release(mark);
        }
    }

    /** {@code Entity#move} keeps a collided move longer than 1e-7 squared or within 1e-7 squared of the delta. */
    private static Vec3 applied(Vec3 delta, Vec3 movement) {
        double length = movement.lengthSqr();
        return length > 1.0E-7D || delta.lengthSqr() - length < 1.0E-7D ? movement : Vec3.ZERO;
    }
}
