package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.world.DolphinBoostState;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.checks.impl.prediction.SimulationContext;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import java.util.Optional;

final class BedrockEntityContactFactory {
    private static final double DOLPHIN_PROXIMITY_HORIZONTAL_RANGE = 10.0D;
    private static final double DOLPHIN_PROXIMITY_VERTICAL_RANGE = 10.0D;

    BedrockEntityContactFactory() {
    }

    EntityContactState create(GrimPlayer player, SimulationContext context) {
        DolphinBoostState dolphinBoostState = new DolphinBoostState(hasDolphinProximity(player, context));
        return new EntityContactState(dolphinBoostState);
    }

    private boolean hasDolphinProximity(GrimPlayer player, SimulationContext context) {
        CompensatedEntities entities = context.getEntities() != null
                ? context.getEntities()
                : player.compensatedEntities;
        if (entities == null || entities.entityMap.isEmpty()) {
            return false;
        }
        Optional<Vec3d> trustedPosition = BedrockProfileState.trustedFeetPosition(context);
        if (trustedPosition.isEmpty()) {
            return false;
        }
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null || entity.isDead || entity.getType() != EntityTypesCompat.DOLPHIN) {
                continue;
            }
            if (isWithinDolphinProximity(trustedPosition.get(), entity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithinDolphinProximity(Vec3d position, PacketEntity entity) {
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        if (box == null) {
            return false;
        }
        double nearestX = clamp(position.x(), box.minX, box.maxX);
        double nearestY = clamp(position.y(), box.minY, box.maxY);
        double nearestZ = clamp(position.z(), box.minZ, box.maxZ);
        double deltaX = position.x() - nearestX;
        double deltaZ = position.z() - nearestZ;
        double horizontalDistanceSquared = deltaX * deltaX + deltaZ * deltaZ;
        double deltaY = Math.abs(position.y() - nearestY);
        return horizontalDistanceSquared <= DOLPHIN_PROXIMITY_HORIZONTAL_RANGE * DOLPHIN_PROXIMITY_HORIZONTAL_RANGE
                && deltaY <= DOLPHIN_PROXIMITY_VERTICAL_RANGE;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
