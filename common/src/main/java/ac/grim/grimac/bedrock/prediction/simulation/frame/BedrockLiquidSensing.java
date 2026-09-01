package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import java.util.Objects;

public final class BedrockLiquidSensing {
    private BedrockLiquidSensing() {
    }

    public static boolean waterHeadInWater(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(dimensions, "dimensions");
        double cameraY = physicalFeetPosition.y() + BedrockActorDimensions.cameraOffset(dimensions);
        return BedrockLiquidGeometry.liquidPointInBlock(
            context.worldState().blockCollisionWorld(),
            physicalFeetPosition.x(),
            cameraY,
            physicalFeetPosition.z(),
            BedrockLiquidKind.WATER
        );
    }

    public static boolean lavaSwimUpApplies(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(dimensions, "dimensions");
        return BedrockLiquidGeometry.intersectsLiquid(
            context.worldState().blockCollisionWorld(),
            BedrockLiquidGeometry.playerLiquidBox(physicalFeetPosition, dimensions, BedrockLiquidKind.LAVA),
            BedrockLiquidKind.LAVA
        );
    }

    public static boolean lavaTravelFlag(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(dimensions, "dimensions");
        return BedrockLiquidGeometry.intersectsLiquid(
            context.worldState().blockCollisionWorld(),
            BedrockLiquidGeometry.playerLiquidBox(physicalFeetPosition, dimensions, BedrockLiquidKind.LAVA),
            BedrockLiquidKind.LAVA
        );
    }

    public static boolean waterSwimUpApplies(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(dimensions, "dimensions");
        return BedrockLiquidGeometry.intersectsLiquid(
            context.worldState().blockCollisionWorld(),
            BedrockLiquidGeometry.playerLiquidBox(physicalFeetPosition, dimensions, BedrockLiquidKind.WATER),
            BedrockLiquidKind.WATER
        );
    }

    public static boolean inWaterFlag(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState dimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(dimensions, "dimensions");

        return BedrockLiquidGeometry.intersectsLiquid(
            context.worldState().blockCollisionWorld(),
            BedrockLiquidGeometry.playerLiquidBox(physicalFeetPosition, dimensions, BedrockLiquidKind.WATER),
            BedrockLiquidKind.WATER
        ) && !BedrockLiquidGeometry.intersectsLiquid(
            context.worldState().blockCollisionWorld(),
            BedrockLiquidGeometry.playerLiquidBox(physicalFeetPosition, dimensions, BedrockLiquidKind.LAVA),
            BedrockLiquidKind.LAVA
        );
    }

    public static boolean inWaterFlag(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition
    ) {
        return inWaterFlag(context, physicalFeetPosition, context.playerDimensionsState());
    }

    public static boolean liquidGravityApplies(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition
    ) {
        return liquidGravityApplies(context, physicalFeetPosition, context.playerDimensionsState());
    }

    public static boolean liquidGravityApplies(
        BedrockMovementContext context,
        Vec3d physicalFeetPosition,
        PlayerDimensionsState sensingDimensions
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(physicalFeetPosition, "physicalFeetPosition");
        Objects.requireNonNull(sensingDimensions, "sensingDimensions");
        Vec3d stateVectorPosition = new Vec3d(
            physicalFeetPosition.x(),
            physicalFeetPosition.y() + sensingDimensions.height() * 0.5D,
            physicalFeetPosition.z()
        );
        return !BedrockLiquidGeometry.centerTopAndBottomNotInAir(
            context.worldState().blockCollisionWorld(),
            stateVectorPosition,
            sensingDimensions
        );
    }
}
