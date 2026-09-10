package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;

public final class BedrockTravelTypeResolver {
    private BedrockTravelTypeResolver() {
    }

    public static BedrockTravelSelection resolve(
        BedrockMovementContext context,
        boolean inWaterFlag,
        boolean lavaTravelFlag,
        boolean onGround,
        boolean actorGliding,
        boolean onClimbable
    ) {
        BedrockTravelType type;
        Medium movementBranch;
        if (context.movementAbilityFlying()) {
            type = BedrockTravelType.PLAYER_FLYING;
            movementBranch = movementBranch(type);
        } else if (inWaterFlag) {
            type = BedrockTravelType.WATER;
            movementBranch = movementBranch(type);
        } else if (lavaTravelFlag) {
            type = BedrockTravelType.LAVA;
            movementBranch = movementBranch(type);
        } else if (actorGliding && !onClimbable) {
            type = BedrockTravelType.GLIDING;
            movementBranch = movementBranch(type);
        } else if (onGround) {
            type = BedrockTravelType.GROUND;
            movementBranch = movementBranch(type);
        } else {
            type = BedrockTravelType.AIR;
            movementBranch = movementBranch(type);
        }

        return new BedrockTravelSelection(
            type,
            movementBranch
        );
    }

    public static boolean waterActive(
        BedrockMovementContext context,
        Vec3d feet,
        PlayerDimensionsState dimensions
    ) {
        return context.liquidMovementMedium() != Medium.LAVA
            && BedrockLiquidSensing.inWaterFlag(context, feet, dimensions);
    }

    public static Medium postMoveBranch(
        BedrockMovementContext context,
        BedrockCollisionFlags flags,
        boolean waterActive
    ) {
        if (context.inLava() || context.liquidMovementMedium() == Medium.LAVA) {
            return Medium.LAVA;
        }
        return waterActive ? Medium.WATER : flags.onGround() ? Medium.GROUND : Medium.AIR;
    }

    private static Medium movementBranch(BedrockTravelType type) {
        return switch (type) {
            case WATER -> Medium.WATER;
            case LAVA -> Medium.LAVA;
            case GROUND -> Medium.GROUND;
            case NONE, PLAYER_FLYING, GLIDING, AIR -> Medium.AIR;
        };
    }
}
