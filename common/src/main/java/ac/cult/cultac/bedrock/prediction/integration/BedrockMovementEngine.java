package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionResult;
import ac.cult.cultac.bedrock.prediction.BedrockVerticalCollisionVerdict;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.integration.BedrockMovementInputFactory.Input;
import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickVelocityDerivation.Derived;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState.Entry;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockImmobileTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockImmobileTick.Result;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation.Candidate;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.Fireworks;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.InsideBlock;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.PistonShulkerPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.StepTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier.ProbeDimensions;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox.AxisEpsilon;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockMovementEngine implements MovementEngine {
    public static final BedrockMovementEngine INSTANCE = new BedrockMovementEngine();
    static final List<UncertaintyHandler> UNCERTAINTY_HANDLERS = List.of(
        new PistonShulkerPush(), new CollisionModifier(), new StepTransform(), new Fireworks(), new InsideBlock()
    );
    private final BedrockMovementInputFactory inputFactory = new BedrockMovementInputFactory();

    private BedrockMovementEngine() {
    }

    public WorldData buildWorld(
        WorldStageBuilder builder,
        CultPlayer player,
        SimulationContext context,
        PredictionResult lastPrediction,
        DesyncStatus lastOnGround
    ) {
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        DesyncStatus grounded = previous == null ? lastOnGround : DesyncStatus.fromBoolean(previous.movementGrounded());
        return builder.generateWorldData(player, context, lastPrediction, grounded);
    }

    public List<PredVector> applyModifiers(
        MovementModifiers modifiers,
        CultPlayer player,
        Set<Vec3> startingVelocities,
        SimulationContext context,
        PredictionResult lastPrediction,
        boolean canTickSkip
    ) {
        return modifiers.applyBedrockModifiers(player, startingVelocities, context, lastPrediction, canTickSkip);
    }

    public List<PredVector> startingVelocities(
        VelocityTransformer transformer,
        CultPlayer player,
        List<PredVector> input,
        SimulationContext context
    ) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        Input bedrockInput = inputFactory.create(player, context);
        if (bedrockInput == null) {
            return BedrockStartingVelocityProfiles.profileStateVelocities(input);
        }

        LinkedHashMap<CandidateKey, PredVector> output = new LinkedHashMap<>();
        for (PredVector vector : input) {
            for (Entry previousEntry : BedrockStartingVelocityProfiles.previousEntriesForJavaStartingVelocity(context, bedrockInput, vector)) {
                BedrockMovementState boundingBoxState = player.bedrockState == null
                    ? previousEntry.state()
                    : player.bedrockState.applyConfirmedBoundingBoxSize(previousEntry.state(), bedrockInput.inputFrame());
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
                    context.isBedrockTeleportTick()
                );
                for (Candidate movementCandidate : BedrockSimulation.candidates(simulationInput)) {
                    BedrockMovementResult movementResult = movementCandidate.movementResult();
                    addCandidate(output, selectedInput, movementCandidate, vector, movementResult, candidateDelta(movementResult));
                    if (!movementResult.steppedUp()) {
                        Vec3d previous = movementResult.previousState().physicalFeetPosition();
                        addCandidate(
                            output,
                            selectedInput,
                            movementCandidate,
                            vector,
                            movementResult,
                            BedrockVectorAdapter.toJava(movementResult.predictedPosition().subtract(previous))
                        );
                    }
                }
            }
        }
        return output.isEmpty()
            ? BedrockStartingVelocityProfiles.profileStateVelocities(input)
            : new ArrayList<>(output.values());
    }

    private static void addCandidate(
        LinkedHashMap<CandidateKey, PredVector> output,
        Input selectedInput,
        Candidate movementCandidate,
        PredVector source,
        BedrockMovementResult movementResult,
        Vec3 delta
    ) {
        BedrockPredVector candidate = new BedrockPredVector(
            selectedInput, movementResult, movementCandidate.mobJumpComponent(), source, delta
        );
        CandidateKey key = new CandidateKey(
            candidate.x, candidate.y, candidate.z, movementResult.horizontalInputLimit(),
            movementResult.predictedState(), movementCandidate.mobJumpComponent()
        );
        PredVector retained = output.putIfAbsent(key, candidate);
        if (retained != null) {
            retained.mergePacketModifierProvenance(candidate);
        }
    }

    public ValidMovements createValidMovements(
        PredVector initialStartingVelocity,
        CultPlayer player,
        PredictionResult result,
        boolean canStep
    ) {
        return new ValidMovements(initialStartingVelocity, player, result, canStep, UNCERTAINTY_HANDLERS);
    }

    public CollideAxisData probeCollisions(
        CollisionModifier modifier,
        CultPlayer player,
        SimulationContext context,
        double minY,
        Vec3 target,
        Vec3 playerPos,
        PredVector initialStartingVelocity,
        SimpleCollisionBox attemptedMovementExtents,
        boolean canStep
    ) {
        if (context.isBedrockTeleportTick()) {
            return BedrockCollisionAxisData.neutral();
        }
        Input input = initialStartingVelocity instanceof BedrockPredVector candidate
            ? candidate.input()
            : inputFactory.create(player, context);
        if (input == null) {
            return BedrockCollisionAxisData.neutral();
        }
        PlayerDimensionsState dimensions = BedrockActorDimensions.committedMovementDimensions(
            input.previousState(), input.movementContext().playerDimensionsState(), input.inputFrame()
        );
        SimpleCollisionBox playerBox = GetBoundingBox.getBoundingBoxFromPosAndSize(
            playerPos.x, playerPos.y, playerPos.z, (float) dimensions.width(), (float) dimensions.height()
        );
        AxisEpsilon epsilon = bedrockCollisionEpsilon(playerBox);
        CollideAxisData result = modifier.probeCollisions(
            player,
            context,
            minY,
            target,
            playerPos,
            attemptedMovementExtents,
            new ProbeDimensions(
                (float) dimensions.width(), (float) dimensions.height(), (float) dimensions.height(), epsilon
            )
        );
        canonicalizePacketEquivalentCollisionEndpoints(result, target, epsilon);
        return result;
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
        BedrockPredictionResult candidateResult = (BedrockPredictionResult) candidate.getProfileResult(BedrockPredictionResult.class);
        BedrockPredictionResult currentResult = (BedrockPredictionResult) currentBest.getProfileResult(BedrockPredictionResult.class);
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
        PredictionCarry currentCarry
    ) {
        BedrockPredictionResult bedrockResult = result == null
            ? null
            : (BedrockPredictionResult) result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult != null && bedrockResult.nextTickBaseState() != null) {
            List<Entry> baseEntries = List.of(
                new Entry(bedrockResult.nextTickBaseState(), bedrockResult.nextTickBaseMobJumpComponent())
            );
            if (player != null && bedrockResult.movementResult() != null && acceptedDiff != null) {
                recordEndTickBlockPush(result, bedrockResult.movementResult(), acceptedDiff);
                boolean claimedVerticalCollision = result.getSimulationContext().getBedrockInput()
                    .hasRawInputFlag(PlayerAuthInputData.VERTICAL_COLLISION);
                baseEntries = BedrockVerticalCollisionSelection.apply(
                    baseEntries, bedrockResult.movementResult(), claimedVerticalCollision, bedrockResult.verticalCollisionVerdict()
                );
                Derived derived = BedrockNextTickVelocityDerivation.fromAcceptedDiff(
                    bedrockResult.movementResult(),
                    baseEntries,
                    acceptedDiff,
                    likelyHorizontalCollision(result.getCollideAxisData(), true),
                    likelyHorizontalCollision(result.getCollideAxisData(), false),
                    selectedStep(result)
                );
                List<Entry> carriedEntries = derived.entries().isEmpty() ? baseEntries : derived.entries();
                carriedEntries = BedrockRejectedVerticalFlags.apply(
                    bedrockResult, carriedEntries,
                    CultAPI.INSTANCE.getConfigManager().getBedrockMovementPositionFlagThreshold()
                );
                result.setProfileResult(bedrockResult.withNextTickBaseState(carriedEntries.getFirst().state()));
                return new PredictionCommit(new BedrockNextTickStates(carriedEntries), derived.startingVelocities());
            } else {
                return new PredictionCommit(
                    new BedrockNextTickStates(baseEntries),
                    BedrockNextTickVelocityDerivation.profileStateVelocities(baseEntries)
                );
            }
        }
        return new PredictionCommit(null, Set.of());
    }

    static void recordEndTickBlockPush(PredictionResult result, BedrockMovementResult movement, Vec3 acceptedDiff) {
        var context = result.getSimulationContext();
        var dimensions = movement.movementContext().playerDimensionsState();
        Vec3d endpoint = movement.previousState().physicalFeetPosition().add(BedrockVectorAdapter.toBedrock(acceptedDiff));
        SimpleCollisionBox box = GetBoundingBox.getBoundingBoxFromPosAndSize(
            endpoint.x(), endpoint.y(), endpoint.z(), (float) dimensions.width(), (float) dimensions.height());
        List<SimpleCollisionBox> shapes = movement.movementContext().worldState().blockCollisionWorld().collisionBoxes().stream()
            .map(shape -> new SimpleCollisionBox(shape.minX(), shape.minY(), shape.minZ(), shape.maxX(), shape.maxY(), shape.maxZ()))
            .toList();
        context.getWorldData().setMightBeInBlock(context.getVehicle() == null
            && WorldStageBuilder.moveTowardsClosestSpaceBedrock(box, shapes));
    }

    public PredictionSetbackState prepareSetbackState(CultPlayer player, PredictionResult result) {
        BedrockPredictionResult bedrockResult = result == null
            ? null
            : (BedrockPredictionResult) result.getProfileResult(BedrockPredictionResult.class);
        if (bedrockResult == null || bedrockResult.movementResult() == null) {
            return null;
        }
        BedrockMovementState continuation = bedrockResult.movementResult().predictedState();
        Entry entry = new Entry(continuation, bedrockResult.nextTickBaseMobJumpComponent());
        Vec3 predictedDiff = BedrockVectorAdapter.toJava(
            continuation.physicalFeetPosition().subtract(bedrockResult.movementResult().previousState().physicalFeetPosition())
        );
        BedrockCollisionFlags predictedFlags = continuation.collisionFlags();

        Derived derived = BedrockNextTickVelocityDerivation.fromAcceptedDiff(
            bedrockResult.movementResult(),
            List.of(entry),
            predictedDiff,
            predictedFlags.xCollision(),
            predictedFlags.zCollision(),
            bedrockResult.movementResult().steppedUp()
        );
        List<Entry> entries = derived.entries().isEmpty() ? List.of(entry) : derived.entries();
        PredictionCommit commit = new PredictionCommit(
            new BedrockNextTickStates(entries),
            BedrockNextTickVelocityDerivation.profileStateVelocities(entries)
        );
        BedrockMovementState canonical = entries.getFirst().state();
        return new PredictionSetbackState(
            commit,
            BedrockVectorAdapter.toJava(canonical.physicalFeetPosition()),
            BedrockVectorAdapter.toJava(canonical.velocity()),
            canonical.movementGrounded()
        );
    }

    public PredictionSetbackState captureSetbackState(PredictionCommit commit) {
        if (commit != null && commit.carry() instanceof BedrockNextTickStates nextTickStates
            && !nextTickStates.profileEntries().isEmpty()) {
            BedrockMovementState canonical = nextTickStates.profileEntries().getFirst().state();
            return new PredictionSetbackState(
                commit,
                BedrockVectorAdapter.toJava(canonical.physicalFeetPosition()),
                BedrockVectorAdapter.toJava(canonical.velocity()),
                canonical.movementGrounded()
            );
        }
        return null;
    }

    public PredictionCommit applyTeleportToCarry(PredictionCarry carry, TeleportData teleport) {
        if (teleport == null || !(carry instanceof BedrockNextTickStates nextTickStates)) {
            return null;
        }
        Vec3d position = BedrockVectorAdapter.toBedrock(teleport.getLocation());
        List<Entry> rebasedEntries = nextTickStates.profileEntries().stream().map(entry -> {
            BedrockMovementState state = entry.state();

            // MovePlayer TELEPORT resets velocity; any subsequent SetEntityMotion
            // enters through the existing packet modifiers, not Java teleport delta flags.
            Vec3d velocity = Vec3d.ZERO;
            BedrockMovementState rebased = restoreCorrectedState(state, position, velocity);
            if (teleport.getBedrockOnGround() != null) {

                rebased = rebased.withVelocityAndCollisionFlags(
                    rebased.velocity(), rebased.collisionFlags().withOnGround(teleport.getBedrockOnGround())
                );
            }
            return entry.withState(rebased);
        }).toList();
        return rebasedEntries.isEmpty()
            ? null
            : new PredictionCommit(
                new BedrockNextTickStates(rebasedEntries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(rebasedEntries)
            );
    }

    public PredictionCommit applyImmobileStateToCarry(
        CultPlayer player,
        PredictionCarry carry,
        AuthoredMovementFrame authoredMovementFrame,
        SimulationContext context
    ) {
        if (authoredMovementFrame == null) {
            return applyImmobileStateToCarry(carry, null, trustedMayFly(player), null);
        }
        if (context == null || context.getAuthoredInput() != authoredMovementFrame) {
            return new PredictionCommit(null, Set.of(Vec3.ZERO));
        }
        Input input = inputFactory.create(player, context);
        return input == null
            ? new PredictionCommit(null, Set.of(Vec3.ZERO))
            : applyImmobileStateToCarry(carry, input.inputFrame(), trustedMayFly(player), input.worldSnapshot());
    }

    public PredictionCarry applyAcknowledgedGlidingToCarry(PredictionCarry carry, boolean actorGliding) {
        return carry instanceof BedrockNextTickStates states
            ? new BedrockNextTickStates(
                states.profileEntries().stream()
                    .map(entry -> entry.withState(entry.state().withGliding(actorGliding)))
                    .toList()
            )
            : carry;
    }

    PredictionCommit applyImmobileStateToCarry(
        PredictionCarry carry,
        BedrockInputFrame frame,
        boolean mayFly,
        BedrockWorldSnapshot snapshot
    ) {
        if (!(carry instanceof BedrockNextTickStates nextTickStates) || nextTickStates.profileEntries().isEmpty()) {
            return new PredictionCommit(null, Set.of(Vec3.ZERO));
        }
        if (frame != null && snapshot == null) {
            return new PredictionCommit(null, Set.of(Vec3.ZERO));
        }
        List<Entry> immobileEntries = nextTickStates.profileEntries().stream()
            .map(entry -> immobileEntry(entry, frame, mayFly, snapshot))
            .toList();
        return new PredictionCommit(
            new BedrockNextTickStates(immobileEntries),
            BedrockNextTickVelocityDerivation.profileStateVelocities(immobileEntries)
        );
    }

    private static Entry immobileEntry(Entry entry, BedrockInputFrame frame, boolean mayFly, BedrockWorldSnapshot snapshot) {
        if (frame == null) {
            return entry.withState(entry.state().afterImmobileBoundary());
        }
        Result result = BedrockImmobileTick.advance(
            entry.state(), frame, Objects.requireNonNull(snapshot), entry.mobJumpComponent(), mayFly
        );
        return new Entry(result.state(), result.mobJumpComponent());
    }

    private static boolean trustedMayFly(CultPlayer player) {
        return player != null && player.canFly;
    }

    private static BedrockMovementState restoreCorrectedState(BedrockMovementState state, Vec3d position, Vec3d velocity) {
        return state.afterServerTeleport(position, velocity);
    }

    private static boolean likelyHorizontalCollision(CollideAxisData collision, boolean xAxis) {
        if (collision == null) {
            return false;
        }
        CollideResult axis = xAxis ? collision.getX() : collision.getZ();
        return axis != null && axis.isLikelyCollide();
    }

    private static boolean selectedStep(PredictionResult result) {
        PredVector selected = selectedPredictionVector(result);
        return selected != null && selected.isBoundedStep();
    }

    private static boolean attachCandidateMetadata(PredictionResult result) {
        PredVector selected = selectedPredictionVector(result);
        BedrockPredVector candidate = selected == null ? null : (BedrockPredVector) selected.firstInLineage(BedrockPredVector.class);
        if (candidate == null) {
            return false;
        }
        BedrockMovementResult movementResult = candidate.movementResult();
        BedrockMovementState nextTickBaseState = BedrockValidationSelectedState.nextTickBase(
            movementResult, BedrockValidationSelection.from(result, movementResult)
        );
        boolean claimedVerticalCollision = result.getSimulationContext().getBedrockInput()
            .hasRawInputFlag(PlayerAuthInputData.VERTICAL_COLLISION);
        BedrockVerticalCollisionVerdict verticalCollisionVerdict = BedrockVerticalCollisionClassifier.classify(
            result, movementResult.predictedState(), claimedVerticalCollision
        );
        result.setProfileResult(new BedrockPredictionResult(
            movementResult,
            null,
            nextTickBaseState,
            candidate.mobJumpComponent(),
            verticalCollisionVerdict,
            BedrockVerticalCollisionClassifier.claimMatchesCandidate(
                claimedVerticalCollision, movementResult.predictedState()
            )
        ));
        return true;
    }

    private static PredVector selectedPredictionVector(PredictionResult result) {
        return result.getAcceptedClosestToTarget() instanceof PredVector vector ? vector : null;
    }

    private static Vec3 candidateDelta(BedrockMovementResult movementResult) {
        Vec3d previous = movementResult.previousState().physicalFeetPosition();
        return BedrockVectorAdapter.toJava(movementResult.rawPredictedPhysicalFeetPosition().subtract(previous));
    }

    private static AxisEpsilon bedrockCollisionEpsilon(SimpleCollisionBox box) {
        return new AxisEpsilon(
            bedrockAxisEpsilon(box.minX, box.maxX),
            bedrockAxisEpsilon(box.minY, box.maxY),
            bedrockAxisEpsilon(box.minZ, box.maxZ)
        );
    }

    private static double bedrockAxisEpsilon(double min, double max) {

        double ulp = Math.max(Math.ulp((float) min), Math.ulp((float) max));
        return Math.max(1.0E-6, Math.min(0.001, 4.0 * ulp));
    }

    private static void canonicalizePacketEquivalentCollisionEndpoints(
        CollideAxisData collision,
        Vec3 target,
        AxisEpsilon epsilon
    ) {
        canonicalizeCollisionEndpoint(collision.getX(), target.x, epsilon.x());
        canonicalizeCollisionEndpoint(collision.getYPos(), target.y, epsilon.y());
        canonicalizeCollisionEndpoint(collision.getYNeg(), target.y, epsilon.y());
        canonicalizeCollisionEndpoint(collision.getZ(), target.z, epsilon.z());
    }

    private static void canonicalizeCollisionEndpoint(CollideResult axis, double target, double epsilon) {
        if (axis != null && axis.isLikelyCollide() && Math.abs(axis.getResult() - target) <= epsilon) {
            axis.setResult(target);
        }
    }

    private record CandidateKey(
        double x,
        double y,
        double z,
        double horizontalInputLimit,
        BedrockMovementState predictedState,
        BedrockMobJumpComponentState mobJumpComponent
    ) {
    }
}
