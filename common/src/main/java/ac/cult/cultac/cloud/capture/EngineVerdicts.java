package ac.cult.cultac.cloud.capture;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TransactionVel;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.BlockProperties;

/**
 * The prediction engine's state after each client tick, for that tick's VERDICT, and the conditions it adds. Read at
 * the tick end, after prediction has committed the tick; only touched on the player's packet thread.
 */
public final class EngineVerdicts {
    /** {@code LivingEntity#noJumpDelay} after a jump. */
    private static final int JUMP_DELAY = 10;

    private static final int MAX_FALL = 65535;
    private static final int MAX_INPUT = 1000;
    private static final Vec3 NOT_STUCK = new Vec3(1, 1, 1);
    private static final IntakeWire.Engine NONE =
            new IntakeWire.Engine(0, -1, 0, 0, 0, 0, 0, 0, false, 0, 0, false, false, null);

    private final CultPlayer player;
    private PredictionResult lastResult;
    private IntakeWire.Engine last = NONE;
    private PacketEntitySelf self;
    private Pose pose;
    private int noJumpDelay;
    private int lastVelocity = Integer.MIN_VALUE;
    private long conditions;

    public EngineVerdicts(CultPlayer player) {
        this.player = player;
    }

    /** The conditions the last {@link #read()} found, as {@code 1 << bit}s. */
    public long conditions() {
        return conditions;
    }

    public IntakeWire.Engine read() {
        long bits = 0;
        PacketEntitySelf current = player.compensatedEntities.playerEntity;
        if (self != null && current != self) bits |= 1L << IntakeWire.RESPAWNED;
        self = current;
        boolean usingItem = player.isClientUsingItem();
        SimulationProcessor simulation = player.checkManager.getSimulationProcessor();
        PredictionResult result = simulation.getLastPrediction();
        SimulationContext context = result == null ? null : result.getSimulationContext();
        if (result == lastResult || context == null || context.getWorldData() == null) {
            conditions = bits;
            return unpredicted(usingItem);
        }
        lastResult = result;
        PredVector chosen = result.getInitialStartingVel();
        boolean jumped = chosen.isJump();
        if (chosen.isKnockback()) bits |= 1L << IntakeWire.KNOCKBACK;
        if (jumped) bits |= 1L << IntakeWire.JUMPED;
        if (jumped && chosen.hasReason("Jumping sprinting")) bits |= 1L << IntakeWire.SPRINT_JUMPED;
        if (result.isExempt()) bits |= 1L << IntakeWire.ENGINE_EXEMPT;
        conditions = bits;

        int tick = 0;
        if (ambiguous(result, jumped)) tick |= 1 << IntakeWire.ENGINE_JUMP_AMBIGUOUS;
        if (result.getTarget().y > 0) tick |= 1 << IntakeWire.ENGINE_MOVED_UP;
        if (context.isSneaking() != context.isLastSneaking() || pose != null && pose != player.pose) {
            tick |= 1 << IntakeWire.ENGINE_EYE_UNCERTAIN;
        }
        pose = player.pose;

        int atTest = Math.max(0, noJumpDelay - 1);
        noJumpDelay = jumped ? JUMP_DELAY : player.packetStateData.knownInput.jump() ? atTest : 0;

        double fall = 0;
        double fallMin = 0;
        double fallMax = 0;
        if (simulation.getCurrentPredictionCarry() instanceof JavaPredictionCarry carry) {
            fall = carry.fallDistance();
            fallMin = fall;
            fallMax = fall;
            for (JavaPredictionCarry.State state : carry.states()) {
                fallMin = Math.min(fallMin, state.fallDistance());
                fallMax = Math.max(fallMax, state.fallDistance());
            }
        }

        WorldData world = context.getWorldData();
        int movement = 0;
        if (world.getInWater().determineOptimistically()) movement |= 1 << IntakeWire.CONTEXT_WATER;
        if (world.getInLava().determineOptimistically()) movement |= 1 << IntakeWire.CONTEXT_LAVA;
        if (world.getClimbingAtStart() != null && world.getClimbingAtStart().determineOptimistically()) {
            movement |= 1 << IntakeWire.CONTEXT_CLIMBING;
        }
        if (!NOT_STUCK.equals(context.getLastStuckSpeed())) movement |= 1 << IntakeWire.CONTEXT_STUCK;
        if (world.getHoneySlide() != null && world.getHoneySlide().determineOptimistically()) {
            movement |= 1 << IntakeWire.CONTEXT_HONEY_SLIDE;
        }
        if (world.getBubbleColumn() != null && world.getBubbleColumn().hasBubbleColumn()) {
            movement |= 1 << IntakeWire.CONTEXT_BUBBLE_COLUMN;
        }
        int friction = Math.round(BlockProperties.getMaterialFriction(world.getOnBlock()) * 1000);

        int forward = 0;
        int strafe = 0;
        if (!result.isExempt() && context.getVehicle() == null && !context.usesFallFlyingMovement()) {
            float speed = context.getMaxSpeed(player);
            Vec3 residual = result.getTarget()
                    .subtract(result.getAcceptedClosestToTarget())
                    .multiply(1, 0, 1);
            Vec3 input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(residual, context.getXRot());
            forward = speed > 0 ? hundredths(input.z / speed) : 0;
            strafe = speed > 0 ? hundredths(input.x / speed) : 0;
        }

        boolean velocity = false;
        Integer velocityPing = null;
        TransactionVel applied = null;
        for (TransactionVel modifier : chosen.getPacketModifiers()) {
            if (applied == null || modifier.getTransaction() > applied.getTransaction()) applied = modifier;
        }
        if (applied != null && applied.getTransaction() != lastVelocity) {
            lastVelocity = applied.getTransaction();
            velocity = true;
            velocityPing = player.wireIdOf(applied.getProofTransaction());
        }

        last = new IntakeWire.Engine(
                tick,
                ground(context.getLastOnGround()),
                atTest,
                fall(fall),
                fall(fallMin),
                fall(fallMax),
                movement,
                friction,
                usingItem,
                forward,
                strafe,
                context.isSneaking(),
                velocity,
                velocityPing);
        return last;
    }

    private IntakeWire.Engine unpredicted(boolean usingItem) {
        return new IntakeWire.Engine(
                1 << IntakeWire.ENGINE_UNPREDICTED,
                last.ground(),
                last.noJumpDelay(),
                last.fall(),
                last.fallMin(),
                last.fallMax(),
                last.context(),
                last.friction(),
                usingItem,
                last.forward(),
                last.strafe(),
                last.sneaking(),
                false,
                null);
    }

    /** Whether prediction accepted, with no flags, both a candidate that jumped and one that did not. */
    private static boolean ambiguous(PredictionResult result, boolean jumped) {
        if (result.getRealities() == null) return false;
        for (PredictionResult reality : result.getRealities()) {
            if (reality.getFlagSeverity() == 0
                    && reality.getInitialStartingVel().isJump() != jumped) return true;
        }
        return false;
    }

    private static int ground(DesyncStatus status) {
        return status == DesyncStatus.TRUE ? 1 : status == DesyncStatus.FALSE ? 0 : 2;
    }

    private static int fall(double distance) {
        return (int) Math.min(MAX_FALL, Math.max(0, Math.round(distance * 64)));
    }

    private static int hundredths(double share) {
        if (!Double.isFinite(share)) return 0;
        return (int) Math.max(-MAX_INPUT, Math.min(MAX_INPUT, Math.round(share * 100)));
    }
}
