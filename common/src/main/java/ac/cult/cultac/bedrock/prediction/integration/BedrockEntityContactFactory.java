package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.DolphinBoostState;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.SpinContact;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Optional;

final class BedrockEntityContactFactory {
    private static final float DOLPHIN_PROXIMITY_RANGE = 5.0F;
    private static final float SPIN_RANGE = 1.0F;

    BedrockEntityContactFactory() {}

    EntityContactState create(CultPlayer player, SimulationContext context) {
        return create(player, context, false);
    }

    /** {@code acknowledgedSpin}: the pose the client holds from the server is a spin. */
    EntityContactState create(CultPlayer player, SimulationContext context, boolean acknowledgedSpin) {
        DolphinBoostState dolphinBoostState = new DolphinBoostState(hasDolphinProximity(player, context));
        var entities = context.getEntities() != null ? context.getEntities() : player.compensatedEntities;
        return new EntityContactState(
                dolphinBoostState,
                context.getVehicle() == null
                        && entities != null
                        && entities.getSelf().inVehicle(),
                spinContact(player, context, acknowledgedSpin));
    }

    /**
     * The mobs a spin meets at the start of this tick. Only a tick that can begin in a spin, or launch one, counts
     * them: a launch needs a charge the tick before.
     */
    private SpinContact spinContact(CultPlayer player, SimulationContext context, boolean acknowledgedSpin) {
        CompensatedEntities entities =
                context.getEntities() != null ? context.getEntities() : player.compensatedEntities;
        if (context.getVehicle() != null || entities == null || entities.entityMap.isEmpty()) {
            return SpinContact.NONE;
        }
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        if (!acknowledgedSpin
                && previous != null
                && !previous.riptideSpinActive()
                && previous.riptideChargeTicks() <= 0L) {
            return SpinContact.NONE;
        }
        Optional<Vec3d> trustedPosition = BedrockProfileState.trustedFeetPosition(context);
        if (trustedPosition.isEmpty()) {
            return SpinContact.NONE;
        }
        // The search runs before the tick resizes or moves the box.
        PlayerDimensionsState dimensions =
                previous == null ? PlayerDimensionsState.DEFAULT : previous.playerDimensions();
        SimpleCollisionBox reach = grownPlayerBox(
                trustedPosition.get(),
                dimensions,
                SPIN_RANGE,
                context.getBedrockInput() == null
                        ? BedrockCoordinateFrame.IDENTITY
                        : context.getBedrockInput().getCoordinateFrame());
        int received = player.lastTransactionReceived.get();
        int certain = 0, possible = 0;
        SimpleCollisionBox box = new SimpleCollisionBox(), scratch = new SimpleCollisionBox();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null || entity == entities.getSelf() || !entity.isLivingEntity()) continue;
            boolean rider = entity.getRiding() != null;
            Vec3 shown = entity.clientPhysicalPosition;
            if (shown == null) continue;
            // The client shows a moving mob at Cult's box for it, where it stood the tick before, or a lerp step on.
            boolean atShown = strictlyMeets(entity.bedrockShownBox(player, box), reach);
            Vec3 before = entity.bedrockTickStart(1);
            boolean atBefore = !rider && strictlyMeets(boxAt(player, entity, before, box), reach);
            int stepsLeft = entity.newPacketLocation == null ? 0 : entity.newPacketLocation.bedrockStepsLeft();
            Vec3 target = entity.desyncClientPos;
            boolean atNext = atShown;
            if (!rider && target != null && stepsLeft > 0) {
                atNext = strictlyMeets(
                        boxAt(
                                player,
                                entity,
                                new Vec3(
                                        shown.x + (target.x - shown.x) / stepsLeft,
                                        shown.y + (target.y - shown.y) / stepsLeft,
                                        shown.z + (target.z - shown.z) / stepsLeft),
                                box),
                        reach);
            }
            if (atShown
                    && atBefore
                    && atNext
                    && stepsLeft >= 0
                    && !entity.isDead
                    && entity.hasBedrockClientTick()
                    && entity.hasProvenMovementCandidates(received)) {
                certain++;
                possible++;
            } else if (atShown
                    || atBefore
                    || atNext
                    || strictlyMeets(entity.getPossibleMovementCollisionBoxes(box, scratch), reach)) {
                possible++;
            }
        }
        return possible == 0 ? SpinContact.NONE : new SpinContact(certain, possible);
    }

    private static SimpleCollisionBox boxAt(CultPlayer player, PacketEntity entity, Vec3 feet, SimpleCollisionBox out) {
        return GetBoundingBox.getPacketEntityBoundingBox(out, player, feet.x, feet.y, feet.z, entity);
    }

    static boolean strictlyMeets(SimpleCollisionBox box, SimpleCollisionBox reach) {
        return box != null
                && box.maxX > reach.minX
                && box.minX < reach.maxX
                && box.maxY > reach.minY
                && box.minY < reach.maxY
                && box.maxZ > reach.minZ
                && box.minZ < reach.maxZ;
    }

    /** The player's box at {@code position}, grown by {@code range} on every side. */
    static SimpleCollisionBox grownPlayerBox(
            Vec3d position, PlayerDimensionsState dimensions, float range, BedrockCoordinateFrame frame) {
        double radius = dimensions.radius();
        return new SimpleCollisionBox(
                frame.roundX(position.x() - radius) - range,
                (float) position.y() - range,
                frame.roundZ(position.z() - radius) - range,
                frame.roundX(position.x() + radius) + range,
                (float) (position.y() + dimensions.height()) + range,
                frame.roundZ(position.z() + radius) + range);
    }

    private boolean hasDolphinProximity(CultPlayer player, SimulationContext context) {
        CompensatedEntities entities =
                context.getEntities() != null ? context.getEntities() : player.compensatedEntities;
        if (entities == null || entities.entityMap.isEmpty()) {
            return false;
        }
        Optional<Vec3d> trustedPosition = BedrockProfileState.trustedFeetPosition(context);
        if (trustedPosition.isEmpty()) {
            return false;
        }
        // Scan before the requested pose size is applied.
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        PlayerDimensionsState dimensions =
                previous == null ? PlayerDimensionsState.DEFAULT : previous.playerDimensions();
        return dolphinNear(
                entities,
                trustedPosition.get(),
                dimensions,
                context.getBedrockInput() == null
                        ? BedrockCoordinateFrame.IDENTITY
                        : context.getBedrockInput().getCoordinateFrame());
    }

    /** Whether a dolphin is in reach of a player of {@code dimensions} at {@code position}. */
    static boolean dolphinNear(
            CompensatedEntities entities,
            Vec3d position,
            PlayerDimensionsState dimensions,
            BedrockCoordinateFrame frame) {
        if (entities == null) return false;
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null
                    || entity.isDead
                    || entity.getType() != EntityTypeIds.DOLPHIN
                    || entities.isUntrackedRider(entity)) {
                continue;
            }
            if (isWithinDolphinProximity(position, dimensions, entity.getPossibleMovementCollisionBoxes(), frame)) {
                return true;
            }
        }
        return false;
    }

    static boolean isWithinDolphinProximity(
            Vec3d position, PlayerDimensionsState dimensions, SimpleCollisionBox dolphinBox) {
        return isWithinDolphinProximity(position, dimensions, dolphinBox, BedrockCoordinateFrame.IDENTITY);
    }

    static boolean isWithinDolphinProximity(
            Vec3d position,
            PlayerDimensionsState dimensions,
            SimpleCollisionBox dolphinBox,
            BedrockCoordinateFrame frame) {
        if (dolphinBox == null) {
            return false;
        }
        // Expand the player's AABB by five blocks on every axis.
        double radius = dimensions.radius();
        double minX = frame.roundX(position.x() - radius) - DOLPHIN_PROXIMITY_RANGE;
        double minY = (float) position.y() - DOLPHIN_PROXIMITY_RANGE;
        double minZ = frame.roundZ(position.z() - radius) - DOLPHIN_PROXIMITY_RANGE;
        double maxX = frame.roundX(position.x() + radius) + DOLPHIN_PROXIMITY_RANGE;
        double maxY = (float) (position.y() + dimensions.height()) + DOLPHIN_PROXIMITY_RANGE;
        double maxZ = frame.roundZ(position.z() + radius) + DOLPHIN_PROXIMITY_RANGE;
        // Require strict overlap with the expanded box.
        return dolphinBox.maxX > minX
                && dolphinBox.minX < maxX
                && dolphinBox.maxY > minY
                && dolphinBox.minY < maxY
                && dolphinBox.maxZ > minZ
                && dolphinBox.minZ < maxZ;
    }
}
