package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameFacts;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockFrameSystems;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelOptions;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import java.util.Objects;

public final class BedrockImmobileTick {
    private BedrockImmobileTick() {
    }

    public static Result advance(
            BedrockMovementState current,
            BedrockInputFrame frame,
            BedrockWorldSnapshot baseSnapshot,
            BedrockMobJumpComponentState mobJumpComponent,
            boolean mayFly
    ) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(baseSnapshot, "baseSnapshot");
        Objects.requireNonNull(mobJumpComponent, "mobJumpComponent");

        BedrockWorldSnapshot snapshot = BedrockSnapshotResolver.forState(
                baseSnapshot, current, frame);
        BedrockTravelInput input = new BedrockTravelInput(
                current,
                frame,
                frame.intent(),
                snapshot,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                current.velocity(),
                BedrockTravelOptions.vanilla(false, 0.0D));

        BedrockFrameState prepared = BedrockFrameSystems.prepare(input, mobJumpComponent);
        BedrockFrameFacts facts = prepared.frameFacts();
        BedrockInputIntent intent = prepared.inputIntent();

        boolean nextCrawling = applyOrderedAction(
                current.horizontalPose(),
                intent.pose().startCrawling(),
                intent.pose().stopCrawling());
        boolean nextSneaking = applyOrderedAction(
                current.sneakingTicks() > 0L,
                intent.pose().startSneaking(),
                intent.pose().stopSneaking());

        boolean acceptedStartFlying = intent.fly().start()
                && mayFly
                && !(facts.swimming().nextActorSwimming() && current.wasInWaterFlag());
        boolean flyActionReset = acceptedStartFlying || intent.fly().stop();

        float nextFallDistance = BedrockFallDistance.update(
                current.fallDistance(),
                0.0D,
                flyActionReset
                        || facts.inWater()
                        || facts.climb().climbing()
                        || facts.blockMovementSlowdownState().active(),
                facts.lavaTravelFlag());

        boolean nextGliding = prepared.gliding().activeAfterActions();
        long nextFallFlyTicks = nextGliding ? current.fallFlyTicks() + 1L : 0L;

        BedrockMovementState next = new BedrockMovementState(
                new BedrockMovementState.Motion(
                        current.physicalFeetPosition(),
                        Vec3d.ZERO,
                        0.0D,
                        Vec3d.ZERO,
                        frame,
                        current.collisionFlags()),
                new BedrockMovementState.ActorState(
                        new BedrockMovementState.ContactState(
                                facts.blockMovementSlowdownState(),
                                snapshot.initialClimbableContact(),
                                facts.inWater()),
                        new BedrockMovementState.PoseState(
                                intent.sprint().afterActionEdges(current.sprinting()),
                                facts.swimming().nextActorSwimming(),
                                nextCrawling,
                                prepared.control().itemUseSlowdownActive()),
                        new BedrockMovementState.TravelMode(
                                nextGliding,
                                prepared.gliding().requestAfterActions(),
                                false,
                                current.waterTravelFlag(),
                                current.movementBranch()),
                        facts.boundingBoxMode(),
                        facts.movementDimensions(),
                        current.acknowledgedPlayerDimensions()),
                new BedrockMovementState.TickMemory(
                        current.simulationTick() + 1L,
                        facts.powderSnowTicks(),
                        nextFallFlyTicks,
                        nextFallDistance,
                        facts.swimming().swimAmount(),
                        prepared.riptide().nextChargeTicks(),
                        prepared.riptide().spinActive(),
                        prepared.riptide().spinTicks(),
                        nextSneaking ? current.sneakingTicks() + 1L : 0L,
                        prepared.control().itemUseSlowdownTicks(),
                        prepared.dolphinBoost().endTick()));
        return new Result(next, prepared.mobJumpComponent());
    }

    private static boolean applyOrderedAction(boolean current, boolean start, boolean stop) {
        // The vanilla input handling applies each START action before the
        // corresponding STOP action.
        boolean next = current;
        if (start) {
            next = true;
        }
        if (stop) {
            next = false;
        }
        return next;
    }

    public record Result(
            BedrockMovementState state,
            BedrockMobJumpComponentState mobJumpComponent
    ) {
        public Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(mobJumpComponent, "mobJumpComponent");
        }
    }
}
