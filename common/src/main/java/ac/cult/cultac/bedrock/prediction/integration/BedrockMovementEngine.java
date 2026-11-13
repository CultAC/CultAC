package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.bedrock.prediction.api.BedrockActorOrderRange;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementInputFactory.Input;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockImmobileTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockImmobileTick.Result;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation.Candidate;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.postmove.BedrockBoatLilyPads;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimberFlags;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockMovementEngine implements MovementEngine {
    private static final boolean[] MOVE_ONLY = {false};
    private static final boolean[] HOLD_VARIANTS = {false, true};

    public static final BedrockMovementEngine INSTANCE = new BedrockMovementEngine();
    private final BedrockMovementInputFactory inputFactory = new BedrockMovementInputFactory();

    private BedrockMovementEngine() {}

    public PredictionCommit relocatePassenger(
            PredictionCarry carry, Vec3 position, ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame coordinates) {
        var entries = BedrockProfileState.profileEntries(carry).stream()
                .map(entry -> entry.withState(entry.state()
                        .withCoordinateFrame(coordinates)
                        .withPhysicalFeetPosition(BedrockVectorAdapter.toBedrock(position), 0.0D)))
                .toList();
        return new PredictionCommit(new BedrockNextTickStates(entries), Set.of(Vec3.ZERO));
    }

    public PredictionCommit advancePassenger(CultPlayer player, PredictionCarry carry, SimulationContext context) {
        Input input = inputFactory.create(player, context);
        if (input == null) return new PredictionCommit(carry, Set.of(Vec3.ZERO));
        var entries = BedrockProfileState.profileEntries(carry);
        if (entries.isEmpty()) entries = List.of(new Entry(input.previousState(), input.mobJumpComponent()));
        final var position = input.previousState().physicalFeetPosition();
        var relocated = new BedrockNextTickStates(entries.stream()
                .map(entry -> entry.withState(entry.state()
                        .withCoordinateFrame(input.previousState().coordinateFrame())
                        .withPhysicalFeetPosition(position, 0.0D)))
                .toList());
        return input.actorMovementTick()
                ? applyImmobileStateToCarry(relocated, input.inputFrame(), trustedMayFly(player), input.worldSnapshot())
                : new PredictionCommit(relocated, Set.of(Vec3.ZERO));
    }

    public WorldData buildWorld(
            WorldStageBuilder builder,
            CultPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround) {
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        DesyncStatus grounded = previous == null ? lastOnGround : DesyncStatus.fromBoolean(previous.movementGrounded());
        return builder.generateWorldData(player, context, lastPrediction, grounded);
    }

    public List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            CultPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction) {
        return modifiers.applyBedrockModifiers(player, startingVelocities, context, lastPrediction);
    }

    public List<PredVector> startingVelocities(
            VelocityTransformer transformer, CultPlayer player, List<PredVector> input, SimulationContext context) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Input bedrockInput = inputFactory.create(player, context);
        if (bedrockInput == null) {
            return BedrockStartingVelocityProfiles.profileStateVelocities(input);
        }

        LinkedHashMap<CandidateKey, PredVector> output = new LinkedHashMap<>();
        for (PredVector vector : input) {
            for (Entry previousEntry : BedrockStartingVelocityProfiles.previousEntriesForJavaStartingVelocity(
                    context, bedrockInput, vector)) {
                // A tick whose column the client may not have committed yet is its move or its hold.
                for (boolean held : bedrockInput.chunkHoldUnproven() ? HOLD_VARIANTS : MOVE_ONLY) {
                    Input variant = held ? bedrockInput.heldByUncommittedChunk() : bedrockInput;
                    SimulatedEntry simulated = simulate(player, context, variant, previousEntry);
                    BedrockCollisionSampleRegion sampled = variant.sampledRegion();
                    BedrockCollisionSampleRegion fetched = fetchedRegion(sampled, simulated.candidates());
                    if (fetched != sampled) {
                        // The sample was sized before this tick's impulses. A move they lengthen fetches shapes the
                        // sample does not hold, so the tick is simulated again over the region it fetches.
                        bedrockInput = inputFactory.create(player, context, fetched);
                        variant = held ? bedrockInput.heldByUncommittedChunk() : bedrockInput;
                        simulated = simulate(player, context, variant, previousEntry);
                    }
                    Input selectedInput = simulated.input();
                    BedrockSimulation.Input simulationInput = simulated.simulationInput();
                    for (Candidate movementCandidate : simulated.candidates()) {
                        // The travel's own collided move is the client's move: it enters the runner alone.
                        addCandidate(output, selectedInput, simulationInput, movementCandidate, vector);
                    }
                }
                addShownElsewhere(output, player, context, bedrockInput, previousEntry, vector);
            }
        }
        return output.isEmpty()
                ? BedrockStartingVelocityProfiles.profileStateVelocities(input)
                : new ArrayList<>(output.values());
    }

    private SimulatedEntry simulate(
            CultPlayer player, SimulationContext context, Input bedrockInput, Entry previousEntry) {
        BedrockMovementState boundingBoxState =
                player.bedrockState == null || previousEntry.state().isVehicle()
                        ? previousEntry.state()
                        : player.bedrockState.applyConfirmedBoundingBoxSize(
                                previousEntry.state(), bedrockInput.inputFrame(), context.getScale());
        if (context.isBedrockLerpStep()) boundingBoxState = boundingBoxState.withLerpStep();
        // A tick the chunk view holds takes a teleport without moving and leaves the actor off the ground, so the
        // next tick moves normally from the air. It does not move, so it keeps the collisions its last move reported.
        if (bedrockInput.heldByChunkView()) {
            boundingBoxState = boundingBoxState.withTeleportTaken();
            boundingBoxState = boundingBoxState
                    .withVelocityAndCollisionFlags(
                            boundingBoxState.velocity(), boundingBoxState.collisionFlags().withOnGround(false))
                    .withMovementBranch(Medium.AIR);
        }
        Input selectedInput = bedrockInput.withPreviousState(boundingBoxState, previousEntry.mobJumpComponent());
        BedrockSimulation.Input simulationInput = new BedrockSimulation.Input(
                selectedInput.previousState(),
                selectedInput.inputFrame(),
                selectedInput.inputIntent(),
                selectedInput.worldSnapshot(),
                true,
                selectedInput.maxUpStep(),
                selectedInput.mobJumpComponent(),
                selectedInput.actorMovementTick(),
                context.isBedrockTeleportTick() && !selectedInput.heldByChunkView(),
                BedrockControlInput.control(
                        player.bedrockState.controlFrame(selectedInput.authFrame()), selectedInput.previousState()),
                !selectedInput.previousState().isVehicle()
                        && player.bedrockState.movementEffects.glideBoost(
                                selectedInput.authFrame(), selectedInput.actorMovementTick()));
        simulationInput = player.bedrockState.movementCorrections.prepareInput(simulationInput);
        selectedInput =
                selectedInput.withPreviousState(simulationInput.previousState(), simulationInput.mobJumpComponent());
        return new SimulatedEntry(selectedInput, simulationInput, BedrockForwardTick.simulate(simulationInput));
    }

    /**
     * A client before 1.26.60 may show a solid actor at any position it was sent within its history delay: the tick is
     * also each move with one such actor at one of those positions, so a move that meets it there carries the
     * collision and velocity of that contact.
     */
    private void addShownElsewhere(
            LinkedHashMap<CandidateKey, PredVector> output,
            CultPlayer player,
            SimulationContext context,
            Input bedrockInput,
            Entry previousEntry,
            PredVector vector) {
        List<HardCollidingEntityCollision> actors = context.getHardCollidingEntityCollisionsForMovementTick();
        if (actors == null || actors.stream().allMatch(actor -> actor.alternatives().isEmpty())) return;
        try {
            // Every such actor somewhere it blocks nothing of this move: the candidate with them all gone.
            List<HardCollidingEntityCollision> without =
                    actors.stream().filter(actor -> actor.alternatives().isEmpty()).toList();
            context.cacheHardCollidingEntityCollisionsForMovementTick(without);
            Input clear = inputFactory.create(player, context, bedrockInput.sampledRegion());
            if (clear != null) {
                SimulatedEntry simulated = simulate(player, context, clear, previousEntry);
                for (Candidate movementCandidate : simulated.candidates()) {
                    addCandidate(output, simulated.input(), simulated.simulationInput(), movementCandidate, vector);
                }
            }
            for (int i = 0; i < actors.size(); i++) {
                for (Vec3 offset : actors.get(i).alternatives()) {
                    List<HardCollidingEntityCollision> shifted = new ArrayList<>(actors);
                    shifted.set(i, actors.get(i).shifted(offset));
                    context.cacheHardCollidingEntityCollisionsForMovementTick(List.copyOf(shifted));
                    Input moved = inputFactory.create(player, context, bedrockInput.sampledRegion());
                    if (moved == null) continue;
                    for (boolean held : moved.chunkHoldUnproven() ? HOLD_VARIANTS : MOVE_ONLY) {
                        SimulatedEntry simulated =
                                simulate(player, context, held ? moved.heldByUncommittedChunk() : moved, previousEntry);
                        for (Candidate movementCandidate : simulated.candidates()) {
                            addCandidate(
                                    output,
                                    simulated.input(),
                                    simulated.simulationInput(),
                                    movementCandidate,
                                    vector);
                        }
                    }
                }
            }
        } finally {
            context.cacheHardCollidingEntityCollisionsForMovementTick(actors);
        }
    }

    /** Returns the sampled region itself when it holds every shape the candidates' moves fetch. */
    private static BedrockCollisionSampleRegion fetchedRegion(
            BedrockCollisionSampleRegion sampled, List<Candidate> candidates) {
        if (sampled == null) {
            return null;
        }
        BedrockCollisionSampleRegion fetched = sampled;
        for (Candidate candidate : candidates) {
            WorldCollisionBox fetchBox = candidate.movementResult().collisionFetchBox();
            if (fetchBox != null && !fetched.holds(fetchBox)) {
                fetched = fetched.including(fetchBox);
            }
        }
        return fetched;
    }

    private static void addCandidate(
            LinkedHashMap<CandidateKey, PredVector> output,
            Input selectedInput,
            BedrockSimulation.Input simulationInput,
            Candidate movementCandidate,
            PredVector source) {
        BedrockMovementResult movementResult = movementCandidate.movementResult();
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        Vec3 delta =
                BedrockVectorAdapter.toJava(movementResult.predictedPosition().subtract(previous));
        BedrockPredVector candidate = new BedrockPredVector(
                selectedInput, simulationInput, movementResult, movementCandidate.mobJumpComponent(), source, delta);
        CandidateKey key = new CandidateKey(
                candidate.x,
                candidate.y,
                candidate.z,
                movementResult.horizontalInputLimit(),
                movementResult.predictedState(),
                movementCandidate.mobJumpComponent(),
                movementResult.groundJumpApplied(),
                movementResult.solidActorOrderRange());
        PredVector retained = output.putIfAbsent(key, candidate);
        if (retained != null) {
            retained.mergePacketModifierProvenance(candidate);
            // The move and the hold of an uncommitted column ending alike leave which one the client took unknown.
            if (retained instanceof BedrockPredVector kept
                    && kept.input().heldByChunkView() != selectedInput.heldByChunkView()) kept.chunkHoldAmbiguous();
        }
    }

    public ValidMovements createValidMovements(
            PredVector initialStartingVelocity, CultPlayer player, PredictionResult result) {
        // The travel already collided and stepped: its move is the client's. Only the range the order of its solid
        // actors gives its end widens it.
        return new ValidMovements(
                initialStartingVelocity,
                player,
                result,
                handlers(player, result, initialStartingVelocity));
    }

    private static List<ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler> handlers(
            CultPlayer player, PredictionResult result, PredVector initialStartingVelocity) {
        boolean range = BedrockActorOrderRangeHandler.range(initialStartingVelocity) != null;
        boolean slack = BedrockActorPushHandler.touched(player, result.getSimulationContext());
        if (range && slack) return List.of(BedrockActorOrderRangeHandler.INSTANCE, BedrockActorPushHandler.INSTANCE);
        if (range) return List.of(BedrockActorOrderRangeHandler.INSTANCE);
        return slack ? List.of(BedrockActorPushHandler.INSTANCE) : List.of();
    }

    /** The candidate travel's own collision: the engine's collided move and its collision flags. */
    public CollideAxisData travelCollisions(PredictionResult result) {
        return result.getSimulationContext().isBedrockTeleportTick()
                        || !(result.getInitialStartingVel() instanceof BedrockPredVector candidate)
                ? BedrockCollisionAxisData.neutral()
                : BedrockCollisionAxisData.of(candidate.movementResult());
    }

    public void evaluateCandidate(CultPlayer player, PredictionResult result) {
        if (player.bedrockState != null && result != null && !result.isTeleport()) {
            if (!attachCandidateMetadata(result)) {
                BedrockMovementFlagReporter.addEnginePredictionFailureFlag(player, result);
            }
        }
    }

    public boolean isBetterCandidate(PredictionResult candidate, PredictionResult currentBest) {
        if (currentBest == null || candidate.getFlagSeverity() < currentBest.getFlagSeverity()) {
            return true;
        }
        if (candidate.getFlagSeverity() > currentBest.getFlagSeverity()) {
            return false;
        }
        BedrockPredictionResult candidateResult =
                (BedrockPredictionResult) candidate.getProfileResult(BedrockPredictionResult.class);
        BedrockPredictionResult currentResult =
                (BedrockPredictionResult) currentBest.getProfileResult(BedrockPredictionResult.class);
        return candidateResult != null
                && currentResult != null
                && candidateResult.collisionClaimMatchesCandidate()
                && !currentResult.collisionClaimMatchesCandidate();
    }

    public PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry) {
        BedrockPredictionResult bedrockResult = result == null
                ? null
                : (BedrockPredictionResult) result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult != null && bedrockResult.nextTickBaseState() != null) {
            var movement = bedrockResult.movementResult();
            if (movement.previousState().isBoat() && player != null) breakLilyPads(player, movement);
            var state = bedrockResult.nextTickBaseState();
            var frame = result.getSimulationContext().getBedrockInput();
            if (player != null && frame != null) {
                if (result.getInitialStartingVel() instanceof BedrockPredVector candidate)
                    takeChunkHold(player, candidate, frame);
            }
            Entry entry =
                    new Entry(BedrockForwardTick.finish(movement, state), bedrockResult.nextTickBaseMobJumpComponent());
            if (result.isExempt() && frame.getReportedEndOfTickVelocity() != null) {
                var position = BedrockVectorAdapter.toBedrock(frame.getPosition());
                var velocity = BedrockVectorAdapter.toBedrock(frame.getReportedEndOfTickVelocity());
                entry = entry.withState(entry.state()
                        .withPhysicalFeetPosition(
                                position,
                                position.subtract(movement.previousState().physicalFeetPosition()))
                        .withVelocityAndCollisionFlags(velocity, entry.state().collisionFlags()));
            }
            result.setProfileResult(bedrockResult.withNextTickBaseState(entry.state()));
            List<Entry> entries = List.of(entry);
            return new PredictionCommit(
                    new BedrockNextTickStates(entries),
                    BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
        }

        return new PredictionCommit(null, Set.of());
    }

    /**
     * A tick whose column the client may not have committed yet tells which it did by the move or the hold it took:
     * a move commits that column and every column received before it, a hold leaves it and every later one uncommitted.
     */
    private static void takeChunkHold(CultPlayer player, BedrockPredVector candidate, BedrockAuthInputFrame frame) {
        Input input = candidate.input();
        if (player.bedrockState == null || !input.chunkHoldUnproven()) return;
        if (!input.heldByChunkView()) player.bedrockState.movementEffects.actorTickTaken(frame);
        if (candidate.isChunkHoldAmbiguous()) return;
        var chunks = player.bedrockState.clientChunks;
        var state = input.previousState();
        var position = state.physicalFeetPosition();
        if (input.heldByChunkView()) chunks.held(state.coordinateFrame(), position.x(), position.z(), frame.getClientTick());
        else chunks.moved(state.coordinateFrame(), position.x(), position.z(), frame.getClientTick());
    }

    /** The lily pads the committed boat move reached are gone from the client's world from its next tick. */
    private static void breakLilyPads(CultPlayer player, BedrockMovementResult movement) {
        for (var pad : BedrockBoatLilyPads.reached(
                movement.movementContext().worldState().blockCollisionWorld(),
                movement.previousState().playerDimensions(),
                movement.predictedPosition())) {
            player.compensatedWorld.handleBedrockClientBlockBreak(pad.x(), pad.y(), pad.z());
        }
    }

    /** Applies the configured, bounded position reconciliation after this frame has been validated. */
    public PredictionCommit reconcileCommittedPosition(
            PredictionCommit commit, Vec3 claimedPosition, double maximumStep) {
        if (commit == null || claimedPosition == null || !Double.isFinite(maximumStep) || maximumStep <= 0.0D) {
            return commit;
        }
        List<Entry> entries = BedrockProfileState.profileEntries(commit.carry());
        if (entries.isEmpty()) {
            return commit;
        }

        Vec3d target = BedrockVectorAdapter.toBedrock(claimedPosition);
        List<Entry> reconciled = entries.stream()
                .map(entry -> {
                    BedrockMovementState state = entry.state();
                    Vec3d correction = target.subtract(state.physicalFeetPosition());
                    double distance = correction.length();
                    if (distance == 0.0D) {
                        return entry;
                    }

                    Vec3d adjustment = distance <= maximumStep ? correction : correction.scale(maximumStep / distance);
                    Vec3d position = state.physicalFeetPosition().add(adjustment);
                    BedrockMovementState moved =
                            state.withRelocatedPosition(position, state.lastPhysicalDisplacementSquared());
                    return entry.withState(moved);
                })
                .toList();

        if (reconciled.equals(entries)) {
            return commit;
        }
        return new PredictionCommit(
                new BedrockNextTickStates(reconciled),
                BedrockNextTickVelocityDerivation.profileStateVelocities(reconciled));
    }

    public PredictionSetbackState prepareSetbackState(CultPlayer player, PredictionResult result) {
        BedrockPredictionResult bedrockResult = result == null
                ? null
                : (BedrockPredictionResult) result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null) {
            return null;
        }
        BedrockMovementState continuation = bedrockResult.movementResult().predictedState();
        List<Entry> entries = List.of(new Entry(
                BedrockForwardTick.finish(bedrockResult.movementResult(), continuation),
                bedrockResult.nextTickBaseMobJumpComponent()));
        PredictionCommit commit = new PredictionCommit(
                new BedrockNextTickStates(entries), BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
        BedrockMovementState canonical = entries.getFirst().state();
        return new PredictionSetbackState(
                commit,
                BedrockVectorAdapter.toJava(canonical.physicalFeetPosition()),
                BedrockVectorAdapter.toJava(canonical.velocity()),
                canonical.movementGrounded());
    }

    public PredictionSetbackState captureSetbackState(PredictionCommit commit) {
        if (commit != null
                && commit.carry() instanceof BedrockNextTickStates nextTickStates
                && !nextTickStates.profileEntries().isEmpty()) {
            BedrockMovementState canonical =
                    nextTickStates.profileEntries().getFirst().state();
            return new PredictionSetbackState(
                    commit,
                    BedrockVectorAdapter.toJava(canonical.physicalFeetPosition()),
                    BedrockVectorAdapter.toJava(canonical.velocity()),
                    canonical.movementGrounded());
        }
        return null;
    }

    public PredictionCommit applyTeleportToCarry(PredictionCarry carry, TeleportData teleport) {
        if (teleport == null || !(carry instanceof BedrockNextTickStates nextTickStates)) {
            return null;
        }
        Vec3d position = BedrockVectorAdapter.toBedrock(teleport.getLocation());
        List<Entry> rebasedEntries = nextTickStates.profileEntries().stream()
                .map(entry -> {
                    BedrockMovementState state =
                            entry.state().withCoordinateFrame(teleport.getBedrockCoordinateFrame());

                    // MovePlayer TELEPORT resets velocity; any subsequent SetEntityMotion
                    // enters through the existing packet modifiers, not Java teleport delta flags.
                    Vec3d velocity = Vec3d.ZERO;
                    BedrockMovementState rebased = teleport.isBedrockDimensionChange()
                            ? state.afterDimensionChange(position)
                            : restoreCorrectedState(state, position, velocity);
                    if (teleport.getBedrockOnGround() != null) {

                        rebased = rebased.withVelocityAndCollisionFlags(
                                rebased.velocity(),
                                rebased.collisionFlags().withTeleportOnGround(teleport.getBedrockOnGround()));
                    }
                    return entry.withState(rebased);
                })
                .toList();
        return rebasedEntries.isEmpty()
                ? null
                : new PredictionCommit(
                        new BedrockNextTickStates(rebasedEntries),
                        BedrockNextTickVelocityDerivation.profileStateVelocities(rebasedEntries));
    }

    public PredictionCommit applyImmobileStateToCarry(
            CultPlayer player,
            PredictionCarry carry,
            AuthoredMovementFrame authoredMovementFrame,
            SimulationContext context) {
        if (authoredMovementFrame == null) {
            return applyImmobileStateToCarry(carry, null, trustedMayFly(player), null);
        }
        if (context == null || context.getAuthoredInput() != authoredMovementFrame) {
            return new PredictionCommit(carry, Set.of(Vec3.ZERO));
        }
        Input input = inputFactory.create(player, context);
        if (input == null) {
            return new PredictionCommit(carry, Set.of(Vec3.ZERO));
        }
        BedrockNextTickStates states = carry instanceof BedrockNextTickStates previous
                        && !previous.profileEntries().isEmpty()
                ? previous
                : new BedrockNextTickStates(List.of(new Entry(input.previousState(), input.mobJumpComponent())));
        if (!input.actorMovementTick()) {
            return new PredictionCommit(
                    new BedrockNextTickStates(states.profileEntries().stream()
                            .map(entry -> entry.withState(entry.state().withoutActorMovementTick(input.inputFrame())))
                            .toList()),
                    Set.of(Vec3.ZERO));
        }
        return applyImmobileStateToCarry(states, input.inputFrame(), trustedMayFly(player), input.worldSnapshot());
    }

    public PredictionCarry applyAcknowledgedGlidingToCarry(PredictionCarry carry, boolean actorGliding) {
        return carry instanceof BedrockNextTickStates states
                ? new BedrockNextTickStates(states.profileEntries().stream()
                        .map(entry -> entry.withState(entry.state().withAcknowledgedGliding(actorGliding)))
                        .toList())
                : carry;
    }

    public PredictionCarry applyAcknowledgedSprintingToCarry(PredictionCarry carry, boolean sprinting) {
        return carry instanceof BedrockNextTickStates states
                ? new BedrockNextTickStates(states.profileEntries().stream()
                        .map(entry -> entry.state().isVehicle()
                                ? entry
                                : entry.withState(entry.state().withSprinting(sprinting)))
                        .toList())
                : carry;
    }

    public PredictionCarry applyAcknowledgedClimberFlagsToCarry(PredictionCarry carry, BedrockClimberFlags flags) {
        return carry instanceof BedrockNextTickStates states
                ? new BedrockNextTickStates(states.profileEntries().stream()
                        .map(entry -> entry.state().isVehicle()
                                ? entry
                                : entry.withState(entry.state().withClimberFlags(flags)))
                        .toList())
                : carry;
    }

    @Override
    public PredictionCarry applyAcknowledgedPoseToCarry(
            PredictionCarry carry, Boolean crawling, Boolean swimming, Boolean spinning) {
        return carry instanceof BedrockNextTickStates states
                ? new BedrockNextTickStates(states.profileEntries().stream()
                        .map(entry -> entry.withState(entry.state().withAcknowledgedPose(crawling, swimming, spinning)))
                        .toList())
                : carry;
    }

    PredictionCommit applyImmobileStateToCarry(
            PredictionCarry carry, BedrockInputFrame frame, boolean mayFly, BedrockWorldSnapshot snapshot) {
        if (!(carry instanceof BedrockNextTickStates nextTickStates)
                || nextTickStates.profileEntries().isEmpty()) {
            return new PredictionCommit(null, Set.of(Vec3.ZERO));
        }
        if (frame != null && snapshot == null) {
            return new PredictionCommit(carry, Set.of(Vec3.ZERO));
        }
        List<Entry> immobileEntries = nextTickStates.profileEntries().stream()
                .map(entry -> immobileEntry(entry, frame, mayFly, snapshot))
                .toList();
        return new PredictionCommit(
                new BedrockNextTickStates(immobileEntries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(immobileEntries));
    }

    private static Entry immobileEntry(
            Entry entry, BedrockInputFrame frame, boolean mayFly, BedrockWorldSnapshot snapshot) {
        if (frame == null) {
            return entry.withState(entry.state().afterImmobileBoundary());
        }
        Result result = BedrockImmobileTick.advance(
                entry.state(), frame, Objects.requireNonNull(snapshot), entry.mobJumpComponent(), mayFly);
        return new Entry(result.state(), result.mobJumpComponent());
    }

    private static boolean trustedMayFly(CultPlayer player) {
        return player != null && player.canFly;
    }

    private static BedrockMovementState restoreCorrectedState(
            BedrockMovementState state, Vec3d position, Vec3d velocity) {
        return state.afterServerTeleport(position, velocity);
    }

    private static boolean attachCandidateMetadata(PredictionResult result) {
        PredVector selected = selectedPredictionVector(result);
        BedrockPredVector candidate =
                selected == null ? null : (BedrockPredVector) selected.firstInLineage(BedrockPredVector.class);
        if (candidate == null) {
            return false;
        }
        BedrockMovementResult movementResult = candidate.movementResult();
        // The runner keeps the travel's own move; only the debug allowance moves the selection off it.
        BedrockMovementState nextTickBaseState = selected == candidate
                ? movementResult.predictedState()
                : BedrockValidationSelectedState.candidateBase(
                        movementResult, BedrockValidationSelection.from(result, movementResult));
        boolean claimedVerticalCollision =
                result.getSimulationContext().getBedrockInput().hasRawInputFlag(PlayerAuthInputData.VERTICAL_COLLISION);
        // Auth collision bits belong to the rider. Vehicle contact is produced by its collision result.
        BedrockVerticalCollisionVerdict verticalCollisionVerdict =
                movementResult.previousState().isVehicle()
                        ? BedrockVerticalCollisionVerdict.LEGAL
                        : BedrockVerticalCollisionClassifier.classify(
                                result, movementResult.predictedState(), claimedVerticalCollision);
        result.setProfileResult(new BedrockPredictionResult(
                movementResult,
                null,
                nextTickBaseState,
                candidate.mobJumpComponent(),
                verticalCollisionVerdict,
                movementResult.previousState().isVehicle()
                        || BedrockVerticalCollisionClassifier.claimMatchesCandidate(
                                claimedVerticalCollision, movementResult.predictedState())));
        return true;
    }

    private static PredVector selectedPredictionVector(PredictionResult result) {
        return result.getAcceptedClosestToTarget() instanceof PredVector vector ? vector : null;
    }

    private record SimulatedEntry(Input input, BedrockSimulation.Input simulationInput, List<Candidate> candidates) {}

    private record CandidateKey(
            double x,
            double y,
            double z,
            double horizontalInputLimit,
            BedrockMovementState predictedState,
            BedrockMobJumpComponentState mobJumpComponent,
            boolean groundJumpApplied,
            BedrockActorOrderRange solidActorOrderRange) {}
}
