package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;

public final class RootVehicleInterpolationCollision {
    private static final double SAME_POSITION_DISTANCE_SQ = 1.0E-10D;

    private RootVehicleInterpolationCollision() {
    }

    public static boolean shouldShiftRootInterpolationTarget(CultPlayer player, PacketEntity vehicle,
                                                             Vec3 target, Vec3 delta) {
        if (delta.lengthSqr() <= SAME_POSITION_DISTANCE_SQ) {
            return false;
        }

        Vec3 shiftedTarget = target.add(delta);
        SimpleCollisionBox shiftedBox = GetBoundingBox.getPacketEntityBoundingBox(
                player,
                shiftedTarget.x,
                shiftedTarget.y,
                shiftedTarget.z,
                vehicle
        );
        return Collisions.isEmpty(player, shiftedBox, shiftedTarget.y)
                && hasNoRootVehicleEntityCollision(player, vehicle, shiftedBox);
    }

    private static boolean hasNoRootVehicleEntityCollision(CultPlayer player, PacketEntity vehicle,
                                                           SimpleCollisionBox box) {
        SimpleCollisionBox inflatedBox = box.copy().expand(SimpleCollisionBox.COLLISION_EPSILON);
        if (!hasNoLocalPlayerCollision(player, vehicle, inflatedBox)) {
            return false;
        }

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity.getEntityId() == player.entityID || !canCollideWithRootVehicle(vehicle, entity)) {
                continue;
            }

            if (entity.newPacketLocation != null && inflatedBox.isIntersected(entity.getPossibleCollisionBoxes())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNoLocalPlayerCollision(CultPlayer player, PacketEntity vehicle,
                                                     SimpleCollisionBox inflatedBox) {
        PacketEntity self = player.compensatedEntities.getSelf();
        if (!canCollideWithRootVehicle(vehicle, self)) {
            return true;
        }

        Vec3 localPlayerPosition = localPlayerPositionAtRootEntityTick(player);
        if (!localPlayerIsPushable(player, localPlayerPosition)) {
            return true;
        }

        SimpleCollisionBox playerBox = GetBoundingBox.getPlayerBoundingBox(
                player,
                localPlayerPosition.x,
                localPlayerPosition.y,
                localPlayerPosition.z
        );
        return !inflatedBox.isIntersected(playerBox);
    }

    private static Vec3 localPlayerPositionAtRootEntityTick(CultPlayer player) {
        if (player.packetStateData.receivedMovementThisClientTick
                && !player.compensatedEntities.getSelf().inVehicle()) {
            return new Vec3(player.lastX, player.lastY, player.lastZ);
        }
        return new Vec3(player.x, player.y, player.z);
    }

    private static boolean localPlayerIsPushable(CultPlayer player, Vec3 position) {
        return !player.compensatedEntities.getSelf().isDead
                && player.gamemode != GameMode.SPECTATOR
                && !Collisions.onClimbable(player, position.x, position.y, position.z);
    }

    private static boolean canCollideWithRootVehicle(PacketEntity vehicle, PacketEntity entity) {
        if (vehicle == null || entity == null || entity == vehicle || entity.isDead) {
            return false;
        }

        if (vehicle.hasPassenger(entity) || entity.hasPassenger(vehicle)) {
            return false;
        }

        if (packetEntityCanBeCollidedWith(entity)) {
            return true;
        }

        return vehicleUsesPushableEntityCollision(vehicle) && packetEntityIsPushable(entity);
    }

    private static boolean packetEntityCanBeCollidedWith(PacketEntity entity) {
        return EntityTypeUtil.isBoat(entity.type)
                || entity.type == EntityTypesCompat.SHULKER
                || EntityTypeUtil.isHappyGhast(entity.type);
    }

    private static boolean vehicleUsesPushableEntityCollision(PacketEntity vehicle) {
        return EntityTypeUtil.isBoat(vehicle.type) || vehicle.isMinecart();
    }

    private static boolean packetEntityIsPushable(PacketEntity entity) {
        return entity.isMinecart() || entity.isLivingEntity();
    }
}
