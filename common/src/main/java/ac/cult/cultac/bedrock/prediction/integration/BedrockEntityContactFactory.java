package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.DolphinBoostState;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import java.util.Optional;

final class BedrockEntityContactFactory {
    private static final float DOLPHIN_PROXIMITY_RANGE = 10.0F;

    BedrockEntityContactFactory() {
    }

    EntityContactState create(CultPlayer player, SimulationContext context) {
        DolphinBoostState dolphinBoostState = new DolphinBoostState(hasDolphinProximity(player, context));
        return new EntityContactState(dolphinBoostState);
    }

    private boolean hasDolphinProximity(CultPlayer player, SimulationContext context) {
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
        // Scan before the requested pose size is applied.
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        PlayerDimensionsState dimensions = previous == null
                ? PlayerDimensionsState.DEFAULT : previous.playerDimensions();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null || entity.isDead || entity.getType() != EntityTypesCompat.DOLPHIN) {
                continue;
            }
            if (isWithinDolphinProximity(trustedPosition.get(), dimensions, entity.getPossibleMovementCollisionBoxes(),
                    context.getBedrockInput() == null ? BedrockCoordinateFrame.IDENTITY : context.getBedrockInput().getCoordinateFrame())) {
                return true;
            }
        }
        return false;
    }

    static boolean isWithinDolphinProximity(
            Vec3d position, PlayerDimensionsState dimensions, SimpleCollisionBox dolphinBox
    ) {
        return isWithinDolphinProximity(position, dimensions, dolphinBox, BedrockCoordinateFrame.IDENTITY);
    }

    static boolean isWithinDolphinProximity(Vec3d position, PlayerDimensionsState dimensions,
            SimpleCollisionBox dolphinBox, BedrockCoordinateFrame frame) {
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
        return dolphinBox.maxX > minX && dolphinBox.minX < maxX
                && dolphinBox.maxY > minY && dolphinBox.minY < maxY
                && dolphinBox.maxZ > minZ && dolphinBox.minZ < maxZ;
    }
}
