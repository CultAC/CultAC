package ac.grim.grimac.bedrock.prediction.simulation.collision;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockMoveRequest;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockResolvedMove;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelMoveRequest;
import ac.grim.grimac.bedrock.prediction.simulation.travel.BedrockTravelPlan;
import ac.grim.grimac.bedrock.prediction.world.BedrockClimbableContact;

public final class BedrockMoveSystems {
    private BedrockMoveSystems() {
    }

    public static BedrockCollisionOutput move(BedrockTravelPlan plan) {
        BedrockFrameState frame = plan.frame();
        BedrockMoveRequest request = applyBlockMovementSlowdown(plan);
        if (!frame.branch().glidingTravel()) {
            Vec3d requestedPosition = BedrockTravelMoveRequest.applySneakMovement(
                frame.input(), frame.frameFacts(), request
            );
            request = new BedrockMoveRequest(request.resolvedMove(), requestedPosition);
        }

        BedrockFrameFacts facts = frame.frameFacts();
        BedrockEntityMove.Result blockMove = BedrockEntityMove.move(
            frame.input().previousState(),
            frame.input().inputFrame(),
            facts.blockCollisionWorld(),
            facts.movementDimensions(),
            request.requestedPosition(),
            request.collisionInputVelocity(),
            request.move().y(),
            !frame.branch().glidingTravel() && frame.input().options().canStep(),
            frame.input().options().maxUpStep()
        );
        BedrockClimbableContact nextClimbableContact = frame.input().worldSnapshot().climbableContactAt(
            blockMove.position(), facts.movementDimensions()
        );
        return new BedrockCollisionOutput(blockMove, nextClimbableContact, request);
    }

    private static BedrockMoveRequest applyBlockMovementSlowdown(BedrockTravelPlan plan) {
        BedrockMoveRequest request = plan.moveRequest();
        BlockMovementSlowdownState slowdown = plan.frame().frameFacts().blockMovementSlowdownState();
        if (!slowdown.active()) {
            return request;
        }
        Vec3d slowedMove = slowdown.applyToMoveRequest(request.move());
        BedrockResolvedMove resolvedMove = new BedrockResolvedMove(
            request.lavaSwimUpApplied(), slowedMove, slowedMove
        );
        return new BedrockMoveRequest(
            resolvedMove,
            BedrockTravelMoveRequest.requestedPosition(plan.frame().input().previousState(), slowedMove)
        );
    }
}
