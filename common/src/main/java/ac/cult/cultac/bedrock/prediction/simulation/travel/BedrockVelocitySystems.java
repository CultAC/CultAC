package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelBranch;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInputControl;

public final class BedrockVelocitySystems {
    private BedrockVelocitySystems() {
    }

    public static BedrockTravelPlan plan(BedrockFrameState frame) {
        BedrockTravelBranch branch = frame.branch();
        BedrockFrameFacts facts = frame.frameFacts();
        Vec3d velocity = frame.travelVelocity();

        if (branch.glidingTravel()) {
            velocity = BedrockBlockSurfaceMovement.applyHoneySlideBeforeMove(
                velocity,
                facts.honeySlideState(),
                frame.input().previousState().physicalFeetPosition(),
                facts.movementDimensions()
            );
            velocity = BedrockAerialMovement.glideVelocity(velocity, frame.input().inputFrame());
            return plan(frame, velocity, new BedrockTravelHorizontalControl.Step(0.0D, 1.0D), false);
        }

        BedrockTravelHorizontalControl.Step horizontal = horizontal(frame);
        if (branch.defaultMoveSystems()) {
            velocity = BedrockDefaultMoveClimbVertical.apply(
                velocity,
                facts.climb(),
                facts.rawPowderSnowAtFeetAscendable()
            );
        }
        if (branch.playerFlyingTravel()) {
            velocity = BedrockFlyingTravelMovement.applyVerticalInput(
                velocity,
                frame.input().inputFrame(),
                frame.inputIntent(),
                BedrockLiquidVerticalMovement.descendInput(frame.inputIntent())
            );
        }
        velocity = BedrockBlockSurfaceMovement.applyHoneySlideBeforeMove(
            velocity,
            facts.honeySlideState(),
            frame.input().previousState().physicalFeetPosition(),
            facts.movementDimensions()
        );
        return plan(frame, velocity, horizontal, frame.mobJump().lavaSwimUpApplied());
    }

    private static BedrockTravelPlan plan(
        BedrockFrameState frame,
        Vec3d velocity,
        BedrockTravelHorizontalControl.Step horizontal,
        boolean lavaSwimUpApplied
    ) {
        BedrockTravelMoveVector.Step move = BedrockTravelMoveVector.resolve(
            velocity,
            frame.frameFacts().climb()
        );
        BedrockResolvedMove resolvedMove = new BedrockResolvedMove(
            lavaSwimUpApplied,
            move.move(),
            move.collisionInputVelocity()
        );
        BedrockMoveRequest request = new BedrockMoveRequest(
            resolvedMove,
            BedrockTravelMoveRequest.requestedPosition(frame.input().previousState(), resolvedMove.move())
        );
        return new BedrockTravelPlan(frame, velocity, horizontal, request);
    }

    private static BedrockTravelHorizontalControl.Step horizontal(BedrockFrameState frame) {
        BedrockTravelBranch branch = frame.branch();
        BedrockFrameFacts facts = frame.frameFacts();
        BedrockTravelInputControl.InputControlState control = frame.control();
        if (branch.playerFlyingTravel()) {
            return BedrockFlyingTravelMovement.speed(facts.context(), control.moveInputScale());
        }
        if (branch.airTravel()) {
            return BedrockPlayerAirTravelMovement.resolveHorizontal(frame);
        }
        if (branch.waterTravel()) {
            return BedrockTravelHorizontalControl.resolveWaterTravel(
                frame.input().previousState(), frame.input().inputFrame(), facts.context(),
                facts.effectState(), facts.standingSurfaceState(), control.sprintSpeedInput(),
                control.moveInputScale()
            );
        }
        if (branch.lavaTravel()) {
            return BedrockTravelHorizontalControl.resolveLavaTravel(
                facts.context(), facts.standingSurfaceState(), facts.navigationCanWalkInLava(),
                control.moveInputScale(), frame.input().previousState().movementGrounded()
            );
        }
        return BedrockTravelHorizontalControl.resolveNormalTravel(
            frame.input().previousState(), frame.input().inputFrame(), facts.context(),
            facts.effectState(), facts.standingSurfaceState(), facts.climb(), facts.inPowderSnow(),
            control.sprintSpeedInput(), control.moveInputScale(), true
        );
    }
}
