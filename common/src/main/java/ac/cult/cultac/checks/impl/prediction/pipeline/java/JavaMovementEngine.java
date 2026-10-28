package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;
import ac.cult.cultac.utils.nmsutil.ClientFluidTagOrder;
import ac.cult.cultac.utils.nmsutil.GeyserVelocity;
import ac.cult.cultac.utils.nmsutil.NextTickVelocityDeriver;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class JavaMovementEngine implements MovementEngine {
    public static final JavaMovementEngine INSTANCE = new JavaMovementEngine();

    private JavaMovementEngine() {}

    @Override
    public WorldData buildWorld(
            WorldStageBuilder builder,
            CultPlayer player,
            SimulationContext context,
            PredictionResult lastPrediction,
            DesyncStatus lastOnGround) {
        return builder.generateWorldData(player, context, lastPrediction, lastOnGround);
    }

    @Override
    public List<PredVector> applyModifiers(
            MovementModifiers modifiers,
            CultPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction) {
        if (context.getProfileCarry() instanceof JavaPredictionCarry selected
                && !selected.states().isEmpty()
                && player.checkManager.getSimulationProcessor().getCurrentPredictionCarry()
                        instanceof JavaPredictionCarry committed
                && hasMatchingVelocity(committed, startingVelocities)) {
            var filtered = new java.util.HashSet<Vec3>();
            for (var state : selected.states())
                if (startingVelocities.contains(state.velocity())) filtered.add(state.velocity());
            if (filtered.isEmpty()) return new ArrayList<>();
            startingVelocities = filtered;
        }
        // Minecraft#tick sends CLIENT_TICK_END even without a position packet, so the runner simulates every
        // travel from its carried velocity. ValidMovements#withheldReport checks sendPosition's withheld-position bound.
        return modifiers.applyModifers(player, startingVelocities, context, lastPrediction);
    }

    /** From 26.3, the block-entity pass between the selected travel and the report, when it moved the player. */
    public static JavaPassAfterTravel passAfterTravel(PredictionResult result) {
        if (result.getValidMovements() == null) return null;
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        return trace == null ? null : trace.passAfterTravel();
    }

    @Override
    public List<PredVector> startingVelocities(
            VelocityTransformer transformer, CultPlayer player, List<PredVector> input, SimulationContext context) {
        // LivingEntity#travel adds moveRelative's input to the deltaMovement LivingEntity#aiStep left, before
        // handleOnClimbable and Entity#move: each candidate carries one exact input from here on.
        if (!context.isGliding())
            return JavaVelocityRange.ends(
                    player,
                    context,
                    JavaTravelInputs.withInputs(
                            player,
                            context,
                            transformer.generateMovementVectors(
                                    player, JavaEntityVelocityTicks.beforeTravel(player, context, input))));
        var candidates = new ArrayList<PredVector>(input.size() * 2);
        for (PredVector velocity : input) {
            for (boolean vanilla : new boolean[] {true, false}) {
                var math = new JavaMovementMath(velocity, vanilla);
                candidates.addAll(JavaMovementMath.calculate(
                        player,
                        math,
                        () -> JavaTravelInputs.withInputs(
                                player,
                                context,
                                transformer.generateMovementVectors(
                                        player,
                                        JavaEntityVelocityTicks.beforeTravel(player, context, List.of(math))))));
            }
        }
        List<PredVector> gliding = ElytraTransform.candidates(context, candidates);
        List<PredVector> ends = JavaVelocityRange.ends(player, context, gliding);
        // LivingEntity#updateFallFlyingMovement turns one axis's range into others': a collision can switch inside an
        // axis that had no range before the glide.
        List<PredVector> withGlideWalls = null;
        for (PredVector velocity : gliding) {
            PredVector glided = ElytraTransform.glidedRange(player, context, velocity);
            if (glided == null) continue;
            List<PredVector> walls = JavaVelocityRange.glideWallEnds(player, context, velocity, glided);
            if (walls.isEmpty()) continue;
            if (withGlideWalls == null) withGlideWalls = new ArrayList<>(ends);
            withGlideWalls.addAll(walls);
        }
        return withGlideWalls == null ? ends : withGlideWalls;
    }

    @Override
    public void evaluateCandidate(CultPlayer player, PredictionResult result) {
        result.setJavaReportedMovement(JavaVehicleReport.expectedMovement(player, result));
    }

    /**
     * A clean candidate from the carried velocity beats a clean tick-skip start. Of two equally flagged candidates from
     * the same start (tick skip and packet velocities), the one nearer the client's move is the movement it ran; across
     * other starts the start-velocity sort order stands.
     */
    @Override
    public boolean isBetterCandidate(PredictionResult candidate, PredictionResult currentBest) {
        // LocalPlayer#sendChanges reports the tick's jump bit before the position, including ClientInput#makeJump
        // for auto-jump. An upward pass can hide a ground jump completely: prefer the no-jump travel when both
        // independently simulated passes reach the same endpoint and the reported input rules that jump out.
        if (currentBest != null
                && candidate.getFlagSeverity() == 0.0
                && currentBest.getFlagSeverity() == 0.0
                && candidate.getInitialStartingVel().hasSameStartAs(currentBest.getInitialStartingVel())
                && candidate.getInitialStartingVel().isJump()
                        != currentBest.getInitialStartingVel().isJump()
                && candidate.getPlayer().cameraEntity.isSelf()
                && !candidate.getPlayer().packetStateData.knownInput.jump()) {
            JavaPassAfterTravel pass = passAfterTravel(candidate);
            JavaPassAfterTravel other = passAfterTravel(currentBest);
            if (pass != null
                    && other != null
                    && (pass.result().fixedY() || other.result().fixedY())
                    && samePassEndpoint(
                            pass.result().state().position(),
                            other.result().state().position())) {
                return !candidate.getInitialStartingVel().isJump();
            }
        }
        if (currentBest == null || candidate.getFlagSeverity() != currentBest.getFlagSeverity())
            return candidate.isBetterThan(currentBest);
        if (usedDebugAllowance(candidate) || usedDebugAllowance(currentBest)) {
            // The debug allowance can make candidates from any start fit. What it could not cover counts first;
            // then the candidate nearer before it ran is the move.
            double remaining = candidate.getOffset() - currentBest.getOffset();
            if (Math.abs(remaining) > 1.0E-9) return remaining < 0;
            return candidate.selectionOffset() < currentBest.selectionOffset();
        }
        if (!candidate.getInitialStartingVel().hasSameStartAs(currentBest.getInitialStartingVel()))
            return candidate.isBetterThan(currentBest);
        return candidate.selectionOffset() < currentBest.selectionOffset();
    }

    /** The same absolute double position, allowing only the rounding of its final addition. */
    private static boolean samePassEndpoint(Vec3 a, Vec3 b) {
        return Math.abs(a.x - b.x) <= Math.max(Math.ulp(a.x), Math.ulp(b.x))
                && Math.abs(a.y - b.y) <= Math.max(Math.ulp(a.y), Math.ulp(b.y))
                && Math.abs(a.z - b.z) <= Math.max(Math.ulp(a.z), Math.ulp(b.z));
    }

    @Override
    public ValidMovements createValidMovements(
            PredVector initialStartingVelocity, CultPlayer player, PredictionResult result, boolean canStep) {
        return new ValidMovements(initialStartingVelocity, player, result, canStep);
    }

    @Override
    public PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            PredictionCarry currentCarry) {
        NextTickVelocityDeriver.Scratch scratch =
                player.checkManager.getSimulationProcessor().velocityScratch().take();
        player.compensatedEntities.beginMovementPositionReads();
        try {
            var commit = commitNextTick(player, result, lastPrediction, acceptedDiff, scratch);
            if (!failedChecks(result)
                    && player.provenClientFluidTagOrder == null
                    && commit.carry() instanceof JavaPredictionCarry carry
                    && !carry.states().isEmpty()) {
                var order = carry.states().getFirst().fluidTagOrder();
                if (order != null && carry.states().stream().allMatch(state -> state.fluidTagOrder() == order))
                    player.provenClientFluidTagOrder = order;
            }
            return commit;
        } finally {
            player.compensatedEntities.endMovementPositionReads();
            scratch.release();
        }
    }

    private static PredictionCommit commitNextTick(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            NextTickVelocityDeriver.Scratch scratch) {
        acceptedDiff = acceptedDiff.subtract(result.getSimulationContext().getPreTravelMovement());
        if (result.getJavaReportedMovement() != null) acceptedDiff = JavaFallDistance.movement(result, acceptedDiff);
        else if (result.getSimulationContext().getMovementPatch() != null)
            acceptedDiff = result.getSimulationContext()
                    .getEnd()
                    .subtract(result.getSimulationContext().getStart());
        PredictionCommit selected = commitCandidate(player, result, lastPrediction, acceptedDiff, scratch);
        if (failedChecks(result) || !VelocityCandidates.hasEngineTrace(result)) return selected;
        List<PredictionResult> collisionAlternatives =
                new ArrayList<>(result.getValidMovements().collisionVelocityAlternatives(lastPrediction));
        addCompatiblePatchCandidate(result, collisionAlternatives);
        boolean ranged = addRangeSiblings(result, collisionAlternatives);
        var pinned = debugAllowanceSet(player) ? pinnedMovedRange(result) : null;
        if (debugAllowanceSet(player)) ranged |= addHiddenEnds(result, collisionAlternatives) | pinned != null;
        boolean boxed = result.getValidMovements().getClosestTrace().position().hasReason(JavaForwardMove.SHOVE_BOX);
        List<PredictionResult> family = result.getValidMovements().debugCarried();
        if (collisionAlternatives.isEmpty() && !boxed && family.isEmpty()) return selected;
        boolean exact = contextUsesExactEffects(result.getSimulationContext());
        // Each alternative has already proved its own collisions. Commit its velocity with its own block effects.
        boolean replacePartialWater = replacesPartialWaterInput(result, collisionAlternatives);
        var states = new LinkedHashSet<JavaPredictionCarry.State>();
        if (!replacePartialWater) states.addAll(commitStates(selected));
        // Which spin outcomes each committed candidate reached; -1 once two disagree.
        int spinOutcomes = spinOutcomes(commitStates(selected));
        if (!family.isEmpty()) {
            // The debug allowance's gap was position or deltaMovement, or part of each, and a collision or a pass can
            // hide deltaMovement: carry every velocity its readings leave possible, with each one's block effects.
            for (PredictionResult reading : family) {
                if (reading == result) continue;
                NextTickVelocityDeriver.Scratch nested = scratch.take();
                try {
                    var readingStates =
                            commitStates(commitCandidate(player, reading, lastPrediction, acceptedDiff, nested));
                    states.addAll(readingStates);
                    spinOutcomes = agreed(spinOutcomes, readingStates);
                } finally {
                    nested.release();
                }
            }
        }
        for (PredictionResult other : collisionAlternatives) {
            NextTickVelocityDeriver.Scratch nested = scratch.take();
            try {
                Vec3 otherDiff = other.getJavaReportedMovement() != null
                        ? JavaFallDistance.movement(other, acceptedDiff)
                        : other.getSimulationContext().getMovementPatch() == null
                                ? acceptedDiff
                                : other.getSimulationContext()
                                        .getEnd()
                                        .subtract(other.getSimulationContext().getStart());
                PredictionCommit alternative = commitCandidate(player, other, lastPrediction, otherDiff, nested);
                states.addAll(commitStates(alternative));
                spinOutcomes = agreed(spinOutcomes, commitStates(alternative));
            } finally {
                nested.release();
            }
        }
        // When every candidate reached the same spin outcomes, a rebound is no value between them. Otherwise a hit
        // starts somewhere inside their range, and the hull spans both.
        boolean reboundApart = spinOutcomes != -1;
        if (ranged || boxed) states = withCarriedRange(states, boxed ? movedRange(result) : pinned, reboundApart);
        // The allowance leaves many readings: merge them, with every other alternative, into one state per class.
        if (!family.isEmpty()) states = mergedByVelocity(states, reboundApart);
        var velocities = new LinkedHashSet<Vec3>();
        for (var state : states) velocities.add(state.velocity());
        JavaPredictionCarry carry = (JavaPredictionCarry) selected.carry();
        var stateList = List.copyOf(states);
        if (exact)
            result.getSimulationContext()
                    .getWorldData()
                    .setStuckSpeed(new ac.cult.cultac.utils.data.StuckSpeedData(sharedStuckSpeed(stateList), null));
        return new PredictionCommit(
                new JavaPredictionCarry(
                        carry.actor(),
                        replacePartialWater && !stateList.isEmpty()
                                ? stateList.getFirst().fallDistance()
                                : carry.fallDistance(),
                        exact ? stateList : List.of(),
                        carry.powderContact()),
                velocities);
    }

    /** Which of LivingEntity#checkAutoSpinAttack's outcomes {@code states} hold: 1 without the rebound, 2 with. */
    private static int spinOutcomes(List<JavaPredictionCarry.State> states) {
        int outcomes = 0;
        for (var state : states) outcomes |= state.spinRebound() ? 2 : 1;
        return outcomes;
    }

    private static int agreed(int outcomes, List<JavaPredictionCarry.State> states) {
        return outcomes == spinOutcomes(states) ? outcomes : -1;
    }

    /**
     * The velocity range the shove-boxed travel moved with: the starting range after every handler that maps it
     * (LivingEntity#updateFallFlyingMovement turns a vertical range horizontal), else the starting range.
     */
    private static ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox movedRange(PredictionResult result) {
        var moved = result.getValidMovements().getClosestTrace().position().getVelocityRange();
        return moved != null ? moved : result.getInitialStartingVel().getVelocityRange();
    }

    /**
     * With the debug allowance set, the velocity range the travel moved with on the axes its block-entity pass pinned:
     * the report shows nothing of them, and a transform before the move (LivingEntity#updateFallFlyingMovement) can
     * have mixed another axis's range into them. Null when no pass pinned a ranged axis.
     */
    private static ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox pinnedMovedRange(
            PredictionResult result) {
        JavaPassAfterTravel pass = passAfterTravel(result);
        var moved = result.getValidMovements().getClosestTrace().position().getVelocityRange();
        if (pass == null || moved == null) return null;
        boolean x = pass.result().fixedX(),
                y = pass.result().fixedY(),
                z = pass.result().fixedZ();
        if (!(x && moved.minX != moved.maxX || y && moved.minY != moved.maxY || z && moved.minZ != moved.maxZ))
            return null;
        return new ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox(
                x ? moved.minX : 0,
                y ? moved.minY : 0,
                z ? moved.minZ : 0,
                x ? moved.maxX : 0,
                y ? moved.maxY : 0,
                z ? moved.maxZ : 0);
    }

    private static double component(Vec3 vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    private static boolean debugAllowanceSet(CultPlayer player) {
        Vec3 radius = player.debugUncertainty;
        return radius.x != 0 || radius.y != 0 || radius.z != 0;
    }

    /**
     * With the debug allowance set, a range end that fit on an axis a collision or an edge backoff hid, or any value
     * on an axis this travel's block-entity pass pinned, tells nothing about where in the range the client was: carry
     * the other ends of that axis too, so the whole range survives until a report pins it. Differences on any other
     * axis keep the report's pin.
     */
    private static boolean addHiddenEnds(PredictionResult selected, List<PredictionResult> alternatives) {
        PredVector start = selected.getInitialStartingVel();
        PredVector origin = start.getRangeOrigin();
        if (origin == null) return false;
        JavaPassAfterTravel pass = passAfterTravel(selected);
        MovementTrace trace = selected.getValidMovements().getClosestTrace();
        Vec3 stuck = selected.getSimulationContext().getLastStuckSpeed();
        JavaMoveCollision collision = selected.getCollideAxisData() instanceof JavaMoveCollision move ? move : null;
        boolean[] hidden = new boolean[3];
        for (int axis = 0; axis < 3; axis++) {
            int mine = start.getRangeEnd(axis);
            boolean pinned = pass != null
                    && (axis == 0
                            ? pass.result().fixedX()
                            : axis == 1 ? pass.result().fixedY() : pass.result().fixedZ());
            // Player#maybeBackOffFromEdge shortened the move, or a collision clipped it: the report shows no velocity.
            double requested = component(trace.preCollisionVelocity(), axis) * component(stuck, axis);
            boolean shortened = Math.abs(requested - component(trace.preCollisionMovement(), axis)) > 1.0E-9
                    || collision != null
                            && Math.abs(component(collision.attempted(), axis) - component(collision.collided(), axis))
                                    > 1.0E-9;
            hidden[axis] = mine == -1 || mine == 1 || pinned || shortened;
        }
        boolean added = false;
        for (PredictionResult other : selected.getAnyReality()) {
            if (other == selected || other.getInitialStartingVel().getRangeOrigin() != origin) continue;
            PredVector end = other.getInitialStartingVel();
            boolean differs = false;
            boolean hiddenOnly = true;
            for (int axis = 0; axis < 3; axis++) {
                int theirs = end.getRangeEnd(axis);
                if (start.getRangeEnd(axis) == theirs) continue;
                differs = true;
                if (!hidden[axis] || theirs == JavaVelocityRange.POINT) hiddenOnly = false;
            }
            if (!differs || !hiddenOnly) continue;
            boolean present = false;
            for (PredictionResult alternative : alternatives) present |= alternative == other;
            if (present) continue;
            alternatives.add(other);
            added = true;
        }
        return added;
    }

    /** Whether the debug allowance moved {@code result}'s position. */
    private static boolean usedDebugAllowance(PredictionResult result) {
        return result.getValidMovements() != null
                && result.getValidMovements().getDebugAllowance().lengthSqr() > 0;
    }

    /**
     * The other ends of the selected candidate's velocity range that also matched the report: it did not pin which
     * velocity the client moved with (a landing, a clipped wall or a later shove hid it), so each one's carry stays.
     */
    private static boolean addRangeSiblings(PredictionResult selected, List<PredictionResult> alternatives) {
        PredVector origin = selected.getInitialStartingVel().getRangeOrigin();
        if (origin == null) return false;
        boolean added = false;
        for (PredictionResult other : selected.getRealities()) {
            if (other == selected
                    || other.getFlagSeverity() != 0.0
                    || other.getInitialStartingVel().getRangeOrigin() != origin
                    || alternatives.contains(other)) continue;
            alternatives.add(other);
            added = true;
        }
        return added;
    }

    /**
     * The debug readings' states, one per state other than velocity and fall distance (stuck speed, piston start...).
     * Each carries the hull of the readings' velocities and the range of their fall distances: the readings sample a
     * continuous range, so values between them stay possible. LivingEntity#checkAutoSpinAttack's rebound scales a
     * velocity by -0.2: when {@code reboundApart}, velocities with and without it never share a hull.
     */
    private static LinkedHashSet<JavaPredictionCarry.State> mergedByVelocity(
            LinkedHashSet<JavaPredictionCarry.State> states, boolean reboundApart) {
        // Every velocity each state may hold: its value and, when it is already ranged, its range's corners.
        var velocities = new ArrayList<Vec3>(states.size() * 3);
        var rebounded = new ArrayList<Vec3>();
        for (var state : states) {
            var into = reboundApart && state.spinRebound() ? rebounded : velocities;
            Vec3 velocity = state.velocity();
            into.add(velocity);
            var range = velocity instanceof PredVector vector ? vector.getVelocityRange() : null;
            if (range == null) continue;
            into.add(velocity.add(range.minX, range.minY, range.minZ));
            into.add(velocity.add(range.maxX, range.maxY, range.maxZ));
        }
        var merged = new java.util.LinkedHashMap<JavaPredictionCarry.State, JavaPredictionCarry.State>();
        for (var state : states) {
            var key = state.withVelocity(Vec3.ZERO).withFallDistance(0, 0);
            merged.merge(
                    key,
                    state,
                    (a, b) -> a.withFallDistance(
                            Math.min(a.fallDistance(), b.fallDistance()),
                            Math.max(a.fallDistanceMax(), b.fallDistanceMax())));
        }
        var result = new LinkedHashSet<JavaPredictionCarry.State>(merged.size());
        for (var state : merged.values())
            result.add(state.withVelocity(JavaVelocityRange.hull(
                    state.velocity(), reboundApart && state.spinRebound() ? rebounded : velocities)));
        return result;
    }

    /**
     * Every state carries the hull of the matching ends' velocities, so values between the ends stay possible. When
     * {@code reboundApart}, LivingEntity#checkAutoSpinAttack's -0.2 rebound is no value between them: rebounded states
     * hull only each other.
     */
    private static LinkedHashSet<JavaPredictionCarry.State> withCarriedRange(
            LinkedHashSet<JavaPredictionCarry.State> states,
            ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox kept,
            boolean reboundApart) {
        var velocities = new ArrayList<Vec3>(states.size());
        var rebounded = new ArrayList<Vec3>();
        for (var state : states) (reboundApart && state.spinRebound() ? rebounded : velocities).add(state.velocity());
        var ranged = new LinkedHashSet<JavaPredictionCarry.State>(states.size());
        for (var state : states) {
            Vec3 velocity = JavaVelocityRange.hull(
                    state.velocity(), reboundApart && state.spinRebound() ? rebounded : velocities);
            // A shove box hid the travel's velocity: the next tick keeps its range.
            if (kept != null) {
                PredVector carried = velocity instanceof PredVector vector ? vector : new PredVector(velocity);
                var range = carried.getVelocityRange();
                velocity = carried.withVelocityRange(range == null ? kept : range.copy().union(kept));
            }
            ranged.add(state.withVelocity(velocity));
        }
        return ranged;
    }

    /** Whether the travel failed a check. An exemption drops the flags' severity, not the failed travel. */
    private static boolean failedChecks(PredictionResult result) {
        return result.hasEffectiveFlags() || result.isExempt() && !result.getFlags().isEmpty();
    }

    private static void addCompatiblePatchCandidate(PredictionResult selected, List<PredictionResult> alternatives) {
        JavaMovementPatch patch = selected.getSimulationContext().getMovementPatch();
        var order = selected.getSimulationContext().getFluidTagOrder();
        if (patch == null && order == null) return;
        PredictionResult best = null;
        for (PredictionResult other : selected.getRealities()) {
            if (other == selected
                    || other.getSimulationContext().getMovementPatch() == patch
                            && other.getSimulationContext().getFluidTagOrder() == order
                    || other.getFlagSeverity() != 0.0
                    || !other.getInitialStartingVel().hasSameStartAs(selected.getInitialStartingVel())) continue;
            if (INSTANCE.isBetterCandidate(other, best)) best = other;
        }
        if (best != null) alternatives.add(best);
    }

    /** The stuck speed every state carries, or null when they differ. */
    private static Vec3 sharedStuckSpeed(List<JavaPredictionCarry.State> states) {
        Vec3 required = states.isEmpty() ? null : states.getFirst().stuckSpeed();
        for (var state : states) {
            if (!java.util.Objects.equals(required, state.stuckSpeed())) return null;
        }
        return required;
    }

    private static boolean replacesPartialWaterInput(PredictionResult result, List<PredictionResult> alternatives) {
        return result.getValidMovements().hasPartialWaterInput()
                && alternatives.stream()
                        .anyMatch(other -> other.getValidMovements().isWaterInputCandidate());
    }

    private static List<JavaPredictionCarry.State> commitStates(PredictionCommit commit) {
        JavaPredictionCarry carry = (JavaPredictionCarry) commit.carry();
        if (!carry.states().isEmpty()) return carry.states();
        var states = new ArrayList<JavaPredictionCarry.State>(
                commit.startingVelocities().size());
        for (Vec3 velocity : commit.startingVelocities())
            states.add(new JavaPredictionCarry.State(
                    velocity, carry.fallDistance(), null, null, null, carry.powderContact()));
        return states;
    }

    private static PredictionCommit commitCandidate(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            NextTickVelocityDeriver.Scratch scratch) {
        try (var ignored =
                ClientFluidTagOrder.use(player, result.getSimulationContext().getFluidTagOrder())) {
            return commitCandidateWithFluidOrder(player, result, lastPrediction, acceptedDiff, scratch);
        }
    }

    private static PredictionCommit commitCandidateWithFluidOrder(
            CultPlayer player,
            PredictionResult result,
            PredictionResult lastPrediction,
            Vec3 acceptedDiff,
            NextTickVelocityDeriver.Scratch scratch) {
        if (result.getSimulationContext().getVehicle() != null)
            settleRiddenSupport(player, result, JavaFallDistance.movement(result, acceptedDiff));
        if (contextUsesExactEffects(result.getSimulationContext())) {
            // From 26.3 the report holds the block-entity pass after the travel; the travel's own move excludes it.
            var moved = JavaFallDistance.afterCollision(player, result, acceptedDiff);
            result.setJavaCommittedMove(moved);
            double fallRange = fallRangeAfterMove(player, result, acceptedDiff, moved);
            Set<Vec3> velocities = NextTickVelocityDeriver.beforeBlockEffects(player, result, lastPrediction, scratch);
            JavaInsideBlockEffects.Scan scan = JavaInsideBlockEffects.scan(player, result, moved.movement());
            Vec3 end = result.getSimulationContext().getStart().add(moved.movement());
            var states = new LinkedHashSet<JavaPredictionCarry.State>(velocities.size());
            for (Vec3 velocity : velocities) {
                for (var outcome : beforeBlockEntityPass(player, result, moved, scan, end, velocity)) {
                    var effects = outcome.effects();
                    var pass = passAfterTravel(result);
                    if (pass != null) effects = afterPass(player, result, pass, end, effects);
                    states.add(carriedState(result, moved, end, effects, outcome.spinRebound(), fallRange));
                }
            }
            var stateList = List.copyOf(states);
            var nextVelocities = new LinkedHashSet<Vec3>(states.size());
            for (var state : states) nextVelocities.add(state.velocity());
            // Contexts for subsequent ticks consume the complete per-candidate state below.
            result.getSimulationContext()
                    .getWorldData()
                    .setStuckSpeed(new ac.cult.cultac.utils.data.StuckSpeedData(sharedStuckSpeed(stateList), null));
            var actor = result.getSimulationContext().getVehicle() == null
                    ? player.compensatedEntities.getSelf()
                    : result.getSimulationContext().getVehicle();
            var carry = new JavaPredictionCarry(
                    actor,
                    stateList.isEmpty()
                            ? moved.fallDistance()
                            : stateList.getFirst().fallDistance(),
                    stateList);
            return new PredictionCommit(carry, nextVelocities);
        }
        Set<Vec3> velocities =
                NextTickVelocityDeriver.findVelocitiesForNextTick(player, result, lastPrediction, scratch);
        var afterEntities = new LinkedHashSet<Vec3>();
        for (Vec3 velocity : velocities)
            afterEntities.addAll(JavaEntityVelocityTicks.afterPlayerTick(
                    player,
                    result.getSimulationContext(),
                    result.getInitialStartingVel(),
                    result.getSimulationContext().getEnd(),
                    velocity));
        velocities = afterEntities;
        JavaPredictionCarry carry = JavaFallDistance.commit(player, result, acceptedDiff);
        if (result.getSimulationContext().getVersion().isOlderThan(ClientVersion.V_1_21_2))
            return new PredictionCommit(carry, velocities);
        var moved = JavaFallDistance.afterCollision(player, result, acceptedDiff);
        result.setJavaCommittedMove(moved);
        var scan = JavaInsideBlockEffects.scan(player, result, moved.movement());
        boolean powder = scan != null
                && JavaInsideBlockEffects.resolve(player, result, scan, moved.fallDistance(), Vec3.ZERO, moved.landed())
                        .powderContact();
        return new PredictionCommit(
                new JavaPredictionCarry(carry.actor(), carry.fallDistance(), carry.states(), powder), velocities);
    }

    /**
     * How far above the travel's fall distance the high end of a carried fall distance range ends: Entity#move's fall
     * bookkeeping replayed from the high end. Zero when the state carried one value.
     */
    private static double fallRangeAfterMove(
            CultPlayer player, PredictionResult result, Vec3 acceptedDiff, JavaFallDistance.Moved moved) {
        SimulationContext context = result.getSimulationContext();
        if (!(context.getProfileCarry() instanceof JavaPredictionCarry carry)
                || carry.states().size() != 1
                || !carry.states().getFirst().hasFallDistanceRange()) return 0;
        var state = carry.states().getFirst();
        try {
            context.setProfileCarry(new JavaPredictionCarry(
                    carry.actor(), state.fallDistanceMax(), carry.states(), carry.powderContact()));
            return Math.max(
                    0,
                    JavaFallDistance.afterCollision(player, result, acceptedDiff)
                                    .fallDistance()
                            - moved.fallDistance());
        } finally {
            context.setProfileCarry(carry);
        }
    }

    /** A carried velocity's end-of-tick state after {@code scan}'s block pass. */
    private static JavaPredictionCarry.State carriedState(
            PredictionResult result,
            JavaFallDistance.Moved moved,
            Vec3 end,
            JavaInsideBlockEffects.State effects,
            boolean spinRebound,
            double fallRange) {
        SimulationContext context = result.getSimulationContext();
        var start = context.getPistonStartState();
        // A block callback that reset the travel's fall distance (Entity#makeStuckInBlock...) reset both ends.
        double fallHigh =
                effects.fallDistance() == 0 && moved.fallDistance() != 0 ? 0 : effects.fallDistance() + fallRange;
        return new JavaPredictionCarry.State(
                effects.velocity(),
                effects.fallDistance(),
                effects.stuckSpeed(),
                context.getMovementPatch(),
                context.getMovementPatch() == null
                        ? null
                        : new JavaPredictionCarry.PistonStart(
                                end,
                                moved.landed(),
                                context.getWorldData().getMainSupportingBlockPos(),
                                start.pistonDeltas()),
                effects.powderContact(),
                passAfterTravel(result) == null
                        ? moved.playerEyeFluidContact()
                        : passAfterTravel(result).result().state().playerEyeFluidContact(),
                context.getFluidTagOrder(),
                spinRebound,
                fallHigh);
    }

    record TravelState(JavaInsideBlockEffects.State effects, boolean spinRebound) {}

    /** The same post-travel effects for late-pass selection and for committed carry. */
    static List<TravelState> beforeBlockEntityPass(
            CultPlayer player,
            PredictionResult result,
            JavaFallDistance.Moved moved,
            JavaInsideBlockEffects.Scan scan,
            Vec3 end,
            Vec3 velocity) {
        SimulationContext context = result.getSimulationContext();
        var effects = context.isNoPhysics()
                // Entity#isAffectedByBlocks is false; the old stuck multiplier was never consumed.
                ? new JavaInsideBlockEffects.State(
                        moved.fallDistance(), velocity, context.getRequiredCurrentMoveStuckSpeed())
                : JavaInsideBlockEffects.resolveAfterMove(player, result, scan, moved, velocity);
        var states = new ArrayList<TravelState>();
        // LivingEntity#aiStep: blocks -> spin attack/rebound -> remaining actor tick -> later entity ticks.
        // Deferring the rebound to the next tick would also scale those later entities' pushes and boosts.
        for (var spin : JavaSpinAttack.afterTravel(player, result, end, effects.velocity())) {
            var afterSpin = new JavaInsideBlockEffects.State(
                    effects.fallDistance(), spin.velocity(), effects.stuckSpeed(), effects.powderContact());
            afterSpin = afterActorTick(player, context, moved.movement(), afterSpin);
            afterSpin = GeyserVelocity.applyToState(player, result, end, afterSpin);
            for (Vec3 next : JavaEntityVelocityTicks.afterTravel(
                    player, context, result.getInitialStartingVel(), end, afterSpin.velocity()))
                states.add(new TravelState(new JavaInsideBlockEffects.State(
                        afterSpin.fallDistance(), next, afterSpin.stuckSpeed(), afterSpin.powderContact()),
                        spin.rebound()));
        }
        return states;
    }

    /**
     * The travel's end-of-tick state through the block-entity pass after it: the pass replays from the travel's end,
     * with the onGround and supporting block that travel's Entity#move left, on this state's velocity, stuck speed
     * and fall distance.
     */
    private static JavaInsideBlockEffects.State afterPass(
            CultPlayer player,
            PredictionResult result,
            JavaPassAfterTravel pass,
            Vec3 end,
            JavaInsideBlockEffects.State effects) {
        SimulationContext context = result.getSimulationContext();
        JavaPassState travel = pass.travel();
        var start = new JavaPassState(
                end,
                effects.velocity(),
                travel.onGround(),
                travel.support(),
                effects.stuckSpeed(),
                effects.fallDistance(),
                travel.pistonDeltas(),
                effects.powderContact(),
                travel.inWater(),
                travel.inLava(),
                travel.playerEyeFluidContact());
        JavaPassState after = JavaBlockEntityPass.run(
                        player,
                        context,
                        context.getPassAfterTravel(),
                        start,
                        pass.pose(),
                        context.isSneaking(),
                        context.getStart())
                .state();
        return new JavaInsideBlockEffects.State(
                after.fallDistance(), after.velocity(), after.stuckSpeed(), after.powderContact());
    }

    /**
     * Entity#move's setOnGroundWithMovement on the root vehicle: checkSupportingBlock runs on the move's own
     * verticalCollisionBelow, set when collision changes a downward move's y (a landing or a step up), and
     * getBlockSpeedFactor, the effect block and the next travel's friction read the supporting block it stores. The
     * world data's support, built before the move, holds the vehicle's starting ground.
     */
    private static void settleRiddenSupport(CultPlayer player, PredictionResult result, Vec3 movement) {
        SimulationContext context = result.getSimulationContext();
        JavaPassAfterTravel pass = passAfterTravel(result);
        if (pass != null) movement = movement.subtract(pass.movement());
        Vec3 end = context.getStart().add(movement);
        double requestedY = VelocityCandidates.preCollisionVelocity(result).y * context.getLastStuckSpeed().y;
        boolean collidedBelow = requestedY < 0.0 && !JavaFallDistance.sameMoveY(movement.y, requestedY);
        MainSupportingBlockData support = JavaRiddenTravel.clearsWithoutTravel(context)
                ? context.getLastTickMainSupportingBlockData()
                : player.checkManager
                .getSimulationProcessor()
                .mainSupportingBlockAfter(
                        context.getLastTickMainSupportingBlockData(),
                        context.getFromActualPose().offset(movement),
                        movement,
                        collidedBelow);
        WorldData world = context.getWorldData();
        world.setMainSupportingBlockPos(support);
        world.setOnBlock(BlockProperties.getOnPos(player, support, end));
    }

    private static JavaInsideBlockEffects.State afterActorTick(
            CultPlayer player, SimulationContext context, Vec3 movement, JavaInsideBlockEffects.State state) {
        // Strider#tick -> floatStrider is AFTER LivingEntity's inside-block callbacks.
        if (context.getVehicle() != null
                && context.getVehicle().isStrider()
                && context.getWorldData().isInLava()) {
            Vec3 end = context.getStart().add(movement);
            boolean onSurface = ac.cult.cultac.utils.nmsutil.Above.isAbove(end.y)
                    && !ac.cult.blocksim.data.FluidTags.LAVA.test(
                            ac.cult.cultac.utils.nmsutil.ClientFluidQueries.fluidAt(
                                    player.compensatedWorld,
                                    ac.cult.cultac.protocol.value.BlockPos.containing(end.x, end.y, end.z)
                                            .above()));
            if (!onSurface)
                return new JavaInsideBlockEffects.State(
                        state.fallDistance(),
                        state.velocity().scale(0.5).add(0, 0.05, 0),
                        state.stuckSpeed(),
                        state.powderContact());
        }
        return state;
    }

    private static boolean hasMatchingVelocity(JavaPredictionCarry carry, Set<Vec3> velocities) {
        for (var state : carry.states()) if (velocities.contains(state.velocity())) return true;
        return false;
    }

    public static boolean contextUsesExactEffects(SimulationContext context) {
        return context != null && supportsExactEffects(context.getVersion());
    }

    public static boolean supportsExactEffects(ClientVersion version) {
        // Entity#applyEffectsFromBlocks runs after travel from 1.21.2. JavaInsideBlockEffects supplies
        // the pre-1.21.9 traversal as well as the newer axis ordering and protocol773's precision gate.
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }
}
