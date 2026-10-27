package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityPositionWorlds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

public final class RootVehicleInterpolationCollision {
    private static final double SAME_POSITION_DISTANCE_SQ = 1.0E-10D;

    private RootVehicleInterpolationCollision() {}

    public static boolean shouldShiftRootInterpolationTarget(
            CultPlayer player, PacketEntity vehicle, Vec3 target, Vec3 delta) {
        if (delta.lengthSqr() <= SAME_POSITION_DISTANCE_SQ) {
            return false;
        }

        Vec3 shiftedTarget = target.add(delta);
        SimpleCollisionBox shiftedBox = GetBoundingBox.getPacketEntityBoundingBox(
                player, shiftedTarget.x, shiftedTarget.y, shiftedTarget.z, vehicle);
        if (!player.isBedrockMovement() && player.getClientVersion() == ClientVersion.V_1_21_5) {
            // That release uses makeBoundingBox().move(target + delta), adding the current position twice.
            // 1.21.6 fixes it to makeBoundingBox(target + delta).
            Vec3 physical = vehicle.clientPhysicalPosition;
            shiftedBox = GetBoundingBox.getPacketEntityBoundingBox(player, physical.x, physical.y, physical.z, vehicle)
                    .move(shiftedTarget.x, shiftedTarget.y, shiftedTarget.z);
        }
        JavaCollisionState actor = vehicle == player.compensatedEntities.getEntityInControl()
                ? JavaCollisionState.current(player)
                : JavaCollisionState.of(
                        player,
                        vehicle,
                        vehicle.blockEntityState == null ? 0.0 : vehicle.blockEntityState.fallDistance());
        return !Collisions.getCollisionBoxes(player, shiftedBox, null, true, shiftedTarget.y, actor)
                && hasNoRootVehicleEntityCollision(player, vehicle, shiftedBox);
    }

    /**
     * EntityGetter#getEntityCollisions inflates its test area by 1.0E-7 and keeps every entity whose box strictly
     * intersects that area, so a box only touching the query is selected.
     */
    private static SimpleCollisionBox entityCollisionArea(SimpleCollisionBox query) {
        return query.copy().expand(1.0E-7);
    }

    private static boolean inEntityCollisionArea(SimpleCollisionBox area, SimpleCollisionBox box) {
        return area.intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * The shapes Level#getEntityCollisions gives {@code actor} at {@code actorPosition}, for Entity#collide or
     * Entity#isFree, at each entity's compensated client-tick position.
     */
    public static List<SimpleCollisionBox> entityCollisionBoxes(
            CultPlayer player, PacketEntity actor, Vec3 actorPosition, SimpleCollisionBox query) {
        if (tooSmallForEntityCollisions(query)) return new java.util.ArrayList<>();
        SimpleCollisionBox inflated = entityCollisionArea(query);
        List<SimpleCollisionBox> boxes = EntityPositionWorlds.colliders(player, actor, actorPosition, query);
        var selection = new EntityCollisionSelection(player, actor, actorPosition);
        if (selection.mayCollideWithLocalPlayer()) {
            Vec3 local = localPlayerPositionAtRootEntityTick(player);
            SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, local.x, local.y, local.z);
            if (localPlayerIsPushable(player, local) && inEntityCollisionArea(inflated, box)) boxes.add(box);
        }
        return boxes;
    }

    /** The local player's box as a moving root vehicle meets it, when the player is pushable there. */
    private static List<SimpleCollisionBox> localPlayerBoxes(CultPlayer player) {
        Vec3 position = localPlayerPositionAtRootEntityTick(player);
        var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        if (prediction != null) {
            var pass =
                    ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine.passAfterTravel(prediction);
            if (pass != null) position = pass.travel().position();
        }
        if (!localPlayerIsPushable(player, position)) return List.of();
        return List.of(GetBoundingBox.getPlayerBoundingBox(player, position.x, position.y, position.z));
    }

    /**
     * CollisionGetter#noEntityCollision for {@code actor} at {@code actorPosition}. Java reads one compensated
     * client-tick box per entity and always returns TRUE or FALSE. Bedrock retains its candidate semantics.
     */
    public static DesyncStatus noEntityCollision(
            CultPlayer player, PacketEntity actor, Vec3 actorPosition, SimpleCollisionBox query) {
        if (tooSmallForEntityCollisions(query)) return DesyncStatus.TRUE;
        SimpleCollisionBox inflated = entityCollisionArea(query);
        if (!player.isBedrockMovement()) {
            if (!EntityPositionWorlds.colliders(player, actor, actorPosition, query)
                    .isEmpty()) return DesyncStatus.FALSE;
            var selection = new EntityCollisionSelection(player, actor, actorPosition);
            if (selection.mayCollideWithLocalPlayer()) {
                for (SimpleCollisionBox self : localPlayerBoxes(player))
                    if (inEntityCollisionArea(inflated, self)) return DesyncStatus.FALSE;
            }
            return DesyncStatus.TRUE;
        }
        var selection = new EntityCollisionSelection(player, actor, actorPosition);
        SimpleCollisionBox candidate = new SimpleCollisionBox();
        boolean someBlock = false;
        for (PacketEntity other : player.compensatedEntities.entityMap.values()) {
            if (other.getEntityId() == player.entityID
                    || !selection.mayCollide(other)
                    || player.compensatedEntities.isUntrackedRider(other)) continue;
            int count = other.possibleMovementCollisionBoxCandidateCount();
            int blocking = 0;
            for (int i = 0; i < count; i++) {
                SimpleCollisionBox box =
                        selection.collisionBox(other, other.possibleMovementCollisionBoxCandidate(i, candidate));
                if (inEntityCollisionArea(inflated, box) && selection.collidesAt(other, box)) blocking++;
            }
            if (blocking > 0 && blocking == count) return DesyncStatus.FALSE;
            someBlock |= blocking > 0;
        }
        if (selection.mayCollideWithLocalPlayer()) {
            for (SimpleCollisionBox self : localPlayerBoxes(player))
                if (inEntityCollisionArea(inflated, self)) return DesyncStatus.FALSE;
        }
        return someBlock ? DesyncStatus.UNKNOWN : DesyncStatus.TRUE;
    }

    private static boolean tooSmallForEntityCollisions(SimpleCollisionBox box) {
        return ((box.maxX - box.minX) + (box.maxY - box.minY) + (box.maxZ - box.minZ)) / 3.0 < 1.0E-7;
    }

    private static boolean hasNoRootVehicleEntityCollision(
            CultPlayer player, PacketEntity vehicle, SimpleCollisionBox box) {
        SimpleCollisionBox inflatedBox = entityCollisionArea(box);
        // AbstractInterpolationHandler#adjustInterpolationPosition asks Level#noCollision for the vehicle itself.
        Vec3 vehiclePosition =
                vehicle.clientPhysicalPosition == null ? Collisions.feet(box) : vehicle.clientPhysicalPosition;
        if (!player.isBedrockMovement())
            return entityCollisionBoxes(player, vehicle, vehiclePosition, box).isEmpty();
        var selection = new EntityCollisionSelection(player, vehicle, vehiclePosition);
        if (selection.mayCollideWithLocalPlayer()) {
            Vec3 local = localPlayerPositionAtRootEntityTick(player);
            if (localPlayerIsPushable(player, local)
                    && inEntityCollisionArea(
                            inflatedBox, GetBoundingBox.getPlayerBoundingBox(player, local.x, local.y, local.z)))
                return false;
        }
        SimpleCollisionBox candidate = new SimpleCollisionBox();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity.getEntityId() == player.entityID
                    || entity.newPacketLocation == null
                    || !selection.mayCollide(entity)
                    || player.compensatedEntities.isUntrackedRider(entity)) {
                continue;
            }
            for (int i = 0, count = entity.possibleMovementCollisionBoxCandidateCount(); i < count; i++) {
                SimpleCollisionBox entityBox =
                        selection.collisionBox(entity, entity.possibleMovementCollisionBoxCandidate(i, candidate));
                if (inEntityCollisionArea(inflatedBox, entityBox) && selection.collidesAt(entity, entityBox))
                    return false;
            }
        }
        return true;
    }

    /**
     * Where the local player stands for a read at the current ClientLevel#tickEntities place. A read before the
     * LocalPlayer's place meets it where its tick began. A read after it meets it where its travel left it, and so
     * does every Level#tickBlockEntities move: Minecraft#tick runs the block entities once all entities have ticked.
     */
    private static Vec3 localPlayerPositionAtRootEntityTick(CultPlayer player) {
        var entities = player.compensatedEntities;
        if (player.packetStateData.receivedMovementThisClientTick
                && !entities.getSelf().inVehicle()) {
            var prediction = player.checkManager.getSimulationProcessor().getLastPrediction();
            if (prediction != null && prediction.getSimulationContext() != null) {
                if (entities.clientTickOrder(entities.getSelf()) > entities.tickedThroughOrder())
                    return prediction.getSimulationContext().getStart();
                // From 26.3 LocalPlayer reports after the block entities moved it; earlier it reports its travel end.
                var pass = JavaMovementEngine.passAfterTravel(prediction);
                return pass != null
                        ? pass.travel().position()
                        : prediction.getSimulationContext().getEnd();
            }
            return new Vec3(player.lastX, player.lastY, player.lastZ);
        }
        return new Vec3(player.x, player.y, player.z);
    }

    private static boolean localPlayerIsPushable(CultPlayer player, Vec3 position) {
        return !player.compensatedEntities.getSelf().isDead
                && player.gamemode != GameMode.SPECTATOR
                && !Collisions.onClimbable(player, position.x, position.y, position.z);
    }
}
