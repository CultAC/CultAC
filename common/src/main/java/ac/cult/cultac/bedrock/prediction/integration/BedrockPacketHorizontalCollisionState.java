package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.collision.BedrockCollisionProbe;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

final class BedrockPacketHorizontalCollisionState {
    private BedrockPacketHorizontalCollisionState() {
    }

    static BedrockMovementState applyToValidationSelectedState(
            BedrockMovementState state,
            BedrockMovementContext context,
            BedrockAuthInputFrame authInputFrame,
            Vec3d acceptedDelta
    ) {
        if (state == null || authInputFrame == null) {
            return state;
        }
        BedrockCollisionFlags flags = applyEndpointHorizontalContactAxes(
                state.collisionFlags(),
                context,
                state,
                authInputFrame.hasRawInputFlag(PlayerAuthInputData.HORIZONTAL_COLLISION),
                acceptedDelta);
        return flags.equals(state.collisionFlags())
                ? state
                : state.withVelocityAndCollisionFlags(state.velocity(), flags);
    }

    private static BedrockCollisionFlags applyEndpointHorizontalContactAxes(
            BedrockCollisionFlags flags,
            BedrockMovementContext context,
            BedrockMovementState state,
            boolean packetHorizontalCollision,
            Vec3d acceptedDelta
    ) {
        if (!packetHorizontalCollision
                || acceptedDelta == null
                || context == null
                || flags.xCollision()
                || flags.zCollision()) {
            return flags;
        }
        BedrockCollisionProbe.HorizontalContactAxes axes = BedrockCollisionProbe.horizontalContactAxes(
                state.physicalFeetPosition(),
                context.worldState().blockCollisionWorld(),
                context.playerDimensionsState());
        if (!axes.xContact() && !axes.zContact()) {
            return flags;
        }

        return new BedrockCollisionFlags(
                flags.onGround(),
                true,
                flags.verticalCollision(),
                true,
                flags.liquidClimbOut(),
                flags.verticalCollisionBelow(),
                axes.xContact(),
                axes.zContact());
    }
}
