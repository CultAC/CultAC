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
        return JavaVelocityRange.ends(player, context, ElytraTransform.candidates(context, candidates));
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
        if (currentBest == null
                || candidate.getFlagSeverity() != currentBest.getFlagSeverity()
                || !candidate.getInitialStartingVel().hasSameStartAs(currentBest.getInitialStartingVel())) {
            return candidate.isBetterThan(currentBest);
        }
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
        boolean boxed = result.getValidMovements().getClosestTrace().position().hasReason(JavaForwardMove.SHOVE_BOX);
        if (collisionAlternatives.isEmpty() && !boxed) return selected;
        boolean exact = contextUsesExactEffects(result.getSimulationContext());
        // Each alternative has already proved its own collisions. Commit its velocity with its own block effects.
        boolean replacePartialWater = replacesPartialWaterInput(result, collisionAlternatives);
        var states = new LinkedHashSet<JavaPredictionCarry.State>();
        if (!replacePartialWater) states.addAll(commitStates(selected));
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
            } finally {
                nested.release();
            }
        }
        if (ranged || boxed)
            states = withCarriedRange(states, boxed ? result.getInitialStartingVel().getVelocityRange() : null);
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

    /** Every state carries the hull of the matching ends' velocities, so values between the ends stay possible. */
    private static LinkedHashSet<JavaPredictionCarry.State> withCarriedRange(
            LinkedHashSet<JavaPredictionCarry.State> states, ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox kept) {
        var velocities = new ArrayList<Vec3>(states.size());
        for (var state : states) velocities.add(state.velocity());
        var ranged = new LinkedHashSet<JavaPredictionCarry.State>(states.size());
        for (var state : states) {
            Vec3 velocity = JavaVelocityRange.hull(state.velocity(), velocities);
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
            Set<Vec3> velocities = NextTickVelocityDeriver.beforeBlockEffects(player, result, lastPrediction, scratch);
            JavaInsideBlockEffects.Scan scan = JavaInsideBlockEffects.scan(player, result, moved.movement());
            Vec3 end = result.getSimulationContext().getStart().add(moved.movement());
            var states = new LinkedHashSet<JavaPredictionCarry.State>(velocities.size());
            for (Vec3 velocity : velocities) {
                for (var outcome : beforeBlockEntityPass(player, result, moved, scan, end, velocity)) {
                    var effects = outcome.effects();
                    var pass = passAfterTravel(result);
                    if (pass != null) effects = afterPass(player, result, pass, end, effects);
                    states.add(carriedState(result, moved, end, effects, outcome.spinRebound()));
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

    /** A carried velocity's end-of-tick state after {@code scan}'s block pass. */
    private static JavaPredictionCarry.State carriedState(
            PredictionResult result, JavaFallDistance.Moved moved, Vec3 end, JavaInsideBlockEffects.State effects,
            boolean spinRebound) {
        SimulationContext context = result.getSimulationContext();
        var start = context.getPistonStartState();
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
                spinRebound);
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
