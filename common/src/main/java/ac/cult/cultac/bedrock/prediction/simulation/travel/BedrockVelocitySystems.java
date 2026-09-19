package ac.cult.cultac.bedrock.prediction.simulation.travel;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockAerialMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockBlockSurfaceMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockLiquidVerticalMovement;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMath;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelBranch;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInputControl;

public final class BedrockVelocitySystems {
    private BedrockVelocitySystems() {
    }

    public static BedrockTravelPlan plan(BedrockFrameState frame) {
        BedrockTravelBranch branch = frame.branch();
        BedrockFrameFacts facts = frame.frameFacts();
        Vec3d velocity = frame.travelVelocity();

        if (!frame.input().options().travelActive()) {
            return new BedrockTravelPlan(frame, velocity, new BedrockTravelHorizontalControl.Step(0.0D, 1.0D),
                new BedrockMoveRequest(new BedrockResolvedMove(false, Vec3d.ZERO, Vec3d.ZERO),
                    frame.input().previousState().physicalFeetPosition()));
        }
        if (frame.boat() != null) {
            return plan(frame, velocity, new BedrockTravelHorizontalControl.Step(0.0D, 1.0D), false);
        }
        if (branch.glidingTravel()) {
            velocity = BedrockBlockSurfaceMovement.applyHoneySlideBeforeMove(
                velocity,
                facts.honeySlideState(),
                frame.input().previousState().physicalFeetPosition(),
                facts.movementDimensions()
            );
            velocity = BedrockAerialMovement.glideVelocity(velocity, frame.input().inputFrame(), frame.input().glideBoost());
            return plan(frame, velocity, new BedrockTravelHorizontalControl.Step(0.0D, 1.0D), false);
        }

        BedrockTravelHorizontalControl.Step horizontal = horizontal(frame);
        velocity = applyControl(frame, velocity, horizontal);
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

    private static Vec3d applyControl(BedrockFrameState frame, Vec3d velocity,
                                           BedrockTravelHorizontalControl.Step horizontal) {
        Vec3d control = frame.input().control();
        if (control == null) return velocity;
        float side = (float) control.x();
        float forward = (float) control.z();
        if (frame.input().previousState().isHorse()) {
            side *= 0.5F;
            if (forward <= 0.0F) forward *= 0.25F;
        } else {
            // The packet vector already includes input slowdown. Bound its magnitude
            // by the permitted scale instead of applying that slowdown a second time.
            float magnitude = (float) Math.sqrt(side * side + forward * forward);
            float permitted = frame.control().moveInputScale();
            if (magnitude > permitted) {
                float clamp = permitted / magnitude;
                side *= clamp;
                forward *= clamp;
            }
            side *= 0.98F;
            forward *= 0.98F;
        }
        float lengthSquared = side * side + forward * forward;
        if (lengthSquared < 0.01F * 0.01F) return velocity;
        float length = (float) Math.sqrt(lengthSquared);
        float scale = (float) horizontal.horizontalInputLimit() / Math.max(1.0F, length);
        side *= scale;
        forward *= scale;
        float radians = frame.input().inputFrame().yaw() * BedrockMath.DEGREES_TO_RADIANS;
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        return new Vec3d((float) velocity.x() + (side * cos - forward * sin), velocity.y(),
                (float) velocity.z() + (side * sin + forward * cos));
    }

    private static BedrockTravelHorizontalControl.Step horizontal(BedrockFrameState frame) {
        BedrockTravelBranch branch = frame.branch();
        BedrockFrameFacts facts = frame.frameFacts();
        BedrockTravelInputControl.InputControlState control = frame.control();
        float moveInputScale = frame.input().control() != null && !frame.input().previousState().isVehicle()
            ? 1.0F : control.moveInputScale();
        if (branch.playerFlyingTravel()) {
            return BedrockFlyingTravelMovement.speed(facts.context(), moveInputScale);
        }
        if (branch.airTravel()) {
            return BedrockAirTravelMovement.resolveHorizontal(frame);
        }
        if (branch.waterTravel()) {
            return BedrockTravelHorizontalControl.resolveWaterTravel(
                frame.input().previousState(), frame.input().inputFrame(), facts.context(),
                facts.effectState(), facts.standingSurfaceState(), control.sprintSpeedInput(),
                moveInputScale
            );
        }
        if (branch.lavaTravel()) {
            return BedrockTravelHorizontalControl.resolveLavaTravel(
                facts.context(), facts.standingSurfaceState(), facts.navigationCanWalkInLava(),
                moveInputScale, frame.input().previousState().movementGrounded()
            );
        }
        return BedrockTravelHorizontalControl.resolveNormalTravel(
            frame.input().previousState(), frame.input().inputFrame(), facts.context(),
            facts.effectState(), facts.standingSurfaceState(), facts.climb(), facts.inPowderSnow(),
            control.sprintSpeedInput(), moveInputScale, true
        );
    }
}
