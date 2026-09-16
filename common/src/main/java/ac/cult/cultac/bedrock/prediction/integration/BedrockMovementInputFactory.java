package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputIntent;
import ac.cult.cultac.bedrock.prediction.input.BedrockTickInput;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.CultMath;

final class BedrockMovementInputFactory {
    private final BedrockTickInputBuilder tickInputs;
    private final BedrockWorldSnapshotBuilder worldSnapshots;

    BedrockMovementInputFactory() {
        this.tickInputs = new BedrockTickInputBuilder();
        this.worldSnapshots = new BedrockWorldSnapshotBuilder();
    }

    Input create(
            CultPlayer player,
            SimulationContext context
    ) {
        BedrockAuthInputFrame frame = trustedFrame(player, context);
        if (frame == null) {
            return null;
        }
        BedrockMovementState profilePreviousState = BedrockProfileState.previousState(context);
        boolean actorGliding = profilePreviousState != null ? profilePreviousState.gliding()
                : player.bedrockState.getClientPoseState(frame).gliding();
        BedrockCollisionOverrideCatalog geometry = geometryCatalog();
        BedrockPlayerContext playerContext = BedrockPlayerContext.from(player, context, frame, actorGliding);
        BedrockWorldSnapshot worldSnapshot = worldSnapshots.create(player, context, geometry, frame, playerContext);
        BedrockTickInput tickInput = tickInputs.create(player, frame, playerContext);
        BedrockMobJumpComponentState mobJumpComponent = BedrockProfileState.mobJumpComponent(context);
        BedrockMovementState previousState = BedrockInitialStateFactory.resolve(
                context,
                tickInput.inputFrame(),
                worldSnapshot.movementContext(),
                frame,
                profilePreviousState);
        if (profilePreviousState == null) {
            previousState = previousState.withAcknowledgedPose(null, null, playerContext.pose().spinning())
                .withGliding(actorGliding);
        }
        if (player.bedrockState != null) {
            previousState = player.bedrockState.applyConfirmedBoundingBoxSize(
                    previousState, tickInput.inputFrame());
        }
        worldSnapshot = BedrockSnapshotResolver.forState(
                worldSnapshot,
                previousState,
                tickInput.inputFrame());
        return new Input(
                frame,
                previousState,
                tickInput,
                tickInput.inputFrame().intent(),
                worldSnapshot,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                mobJumpComponent,
                hasAcknowledgedStartChunk(player, context)
        );
    }

    private static BedrockAuthInputFrame trustedFrame(CultPlayer player, SimulationContext context) {
        if (player.bedrockState == null) {
            return null;
        }
        if (context == null) {
            return null;
        }
        BedrockAuthInputFrame frame = context.getBedrockInput();
        if (frame == null || !frame.hasMinimumStrictData()) {
            return null;
        }
        return frame;
    }

    private static boolean hasAcknowledgedStartChunk(CultPlayer player, SimulationContext context) {
        int chunkX = CultMath.floor(context.getStart().x) >> 4;
        int chunkZ = CultMath.floor(context.getStart().z) >> 4;
        CompensatedWorld.CachedChunk chunk = player.compensatedWorld.getChunk(chunkX, chunkZ);
        return chunk != null
                && chunk.getTransaction() <= player.lastTransactionReceived.get();
    }

    public record Input(
            BedrockAuthInputFrame authFrame,
            BedrockMovementState previousState,
            BedrockTickInput tickInput,
            BedrockInputIntent inputIntent,
            BedrockWorldSnapshot worldSnapshot,
            double maxUpStep,
            BedrockMobJumpComponentState mobJumpComponent,
            boolean actorMovementTick
    ) {
        public Input(
                BedrockAuthInputFrame authFrame,
                BedrockMovementState previousState,
                BedrockTickInput tickInput,
                BedrockInputIntent inputIntent,
                BedrockWorldSnapshot worldSnapshot,
                double maxUpStep,
                BedrockMobJumpComponentState mobJumpComponent
        ) {
            this(authFrame, previousState, tickInput, inputIntent, worldSnapshot,
                    maxUpStep, mobJumpComponent, true);
        }

        public Input(
                BedrockAuthInputFrame authFrame,
                BedrockMovementState previousState,
                BedrockTickInput tickInput,
                BedrockWorldSnapshot worldSnapshot
        ) {
            this(
                    authFrame,
                    previousState,
                    tickInput,
                    tickInput.inputFrame().intent(),
                    worldSnapshot,
                    BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                    BedrockMobJumpComponentState.DEFAULT,
                    true);
        }

        public Input {
            java.util.Objects.requireNonNull(authFrame, "authFrame");
            java.util.Objects.requireNonNull(previousState, "previousState");
            java.util.Objects.requireNonNull(tickInput, "tickInput");
            inputIntent = inputIntent == null ? tickInput.inputFrame().intent() : inputIntent;
            java.util.Objects.requireNonNull(worldSnapshot, "worldSnapshot");
            java.util.Objects.requireNonNull(mobJumpComponent, "mobJumpComponent");
            if (!Double.isFinite(maxUpStep) || maxUpStep < 0.0D) {
                maxUpStep = 0.0D;
            }
        }

        public BedrockInputFrame inputFrame() {
            return tickInput.inputFrame();
        }

        public BedrockMovementContext movementContext() {
            return worldSnapshot.movementContext();
        }

        public Input withPreviousEntry(BedrockProfileState.Entry previousEntry) {
            java.util.Objects.requireNonNull(previousEntry, "previousEntry");
            return withPreviousState(previousEntry.state(), previousEntry.mobJumpComponent());
        }

        public Input withPreviousState(BedrockMovementState previousState, BedrockMobJumpComponentState mobJumpComponent) {
            if (java.util.Objects.equals(this.previousState, previousState)) {
                if (java.util.Objects.equals(this.mobJumpComponent, mobJumpComponent)) {
                    return this;
                }
            }
            return new Input(
                    authFrame,
                    previousState,
                    tickInput,
                    inputIntent,
                    BedrockSnapshotResolver.forState(worldSnapshot, previousState, tickInput.inputFrame()),
                    maxUpStep,
                    mobJumpComponent,
                    actorMovementTick);
        }
    }

    private BedrockCollisionOverrideCatalog geometryCatalog() {
        return ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings.catalog();
    }

}
