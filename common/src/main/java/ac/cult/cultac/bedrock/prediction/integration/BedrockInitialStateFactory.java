package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFluidMovementSourceResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFluidStateResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockPowderSnowContactResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import net.minecraft.world.phys.Vec3;

final class BedrockInitialStateFactory {
    private BedrockInitialStateFactory() {
    }

    static BedrockMovementState resolve(
            SimulationContext context,
            BedrockInputFrame inputFrame,
            BedrockMovementContext movementContext,
            BedrockAuthInputFrame authInputFrame,
            BedrockMovementState profilePreviousState
    ) {
        Vec3d start = vec(context.getStart());
        if (profilePreviousState != null) {

            return profilePreviousState.withPhysicalFeetPosition(
                    start,
                    profilePreviousState.lastPhysicalDisplacementSquared());
        }
        BedrockMovementContext fluidContext = BedrockFluidStateResolver.withFluidStateFromBlockWorld(
                movementContext,
                start);
        Vec3d initialVelocity = BedrockFluidMovementSourceResolver.fromContext(
                fluidContext,
                start.y()).appliedDelta();
        boolean onGround = groundedFromBlockWorld(movementContext, start)
                && !BedrockPowderSnowContactResolver.surfaceSink(movementContext, start, false);
        Medium medium = fluidContext.worldState().medium();
        BedrockCollisionFlags initialFlags = medium == Medium.WATER || medium == Medium.LAVA
                ? BedrockCollisionFlags.AIR
                : new BedrockCollisionFlags(onGround, false, onGround);
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                start,
                initialVelocity,
                BedrockInputFrame.idle(Math.max(0L, inputFrame.clientTick() - 1L)),
                initialFlags,
                initialMovementBranch(medium, onGround)
        ).withPlayerDimensions(movementContext.playerDimensionsState(), false)
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(
                        movementContext.worldState().blockCollisionWorld(),
                        start,
                        movementContext.playerDimensionsState().width(),
                        movementContext.playerDimensionsState().height(),
                        movementContext.equipmentState().leatherBoots()));
        state = state.withSprinting(authInputFrame != null && authInputFrame.isSprinting());
        return state;
    }

    private static Medium initialMovementBranch(Medium medium, boolean onGround) {
        if (medium == Medium.WATER || medium == Medium.LAVA) {
            return medium;
        }
        return onGround ? Medium.GROUND : Medium.AIR;
    }

    static boolean groundedFromBlockWorld(BedrockMovementContext context, Vec3d feet) {
        if (context == null) {
            return false;
        }
        return touchesGround(feet, context.playerDimensionsState(), context.worldState().blockCollisionWorld());
    }

    private static boolean touchesGround(
            Vec3d feet,
            PlayerDimensionsState dimensions,
            BlockCollisionWorld blockWorld
    ) {
        if (feet == null || blockWorld.isEmpty()) {
            return false;
        }
        double radius = dimensions.radius();
        double epsilon = 1.0E-6D;
        WorldCollisionBox groundProbeBox = new WorldCollisionBox(
                feet.x() - radius,
                feet.y() - epsilon,
                feet.z() - radius,
                feet.x() + radius,
                feet.y(),
                feet.z() + radius
        );
        for (WorldCollisionBox collisionBox : blockWorld.collisionBoxes()) {
            if (collisionBox.intersects(groundProbeBox)) {
                return true;
            }
        }
        return false;
    }

    private static Vec3d vec(Vec3 value) {
        return new Vec3d(value.x, value.y, value.z);
    }
}
