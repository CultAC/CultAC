package ac.cult.cultac.network;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHelper;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link ValidMovements}' handler chain against the plain chain of handleMovementTrace calls it replaces. */
class ValidMovementsTraceEquivalenceTest {
    private static final int SCENARIOS = 66;

    @Test
    void tracesAndBoxesMatchTheHandlerChainOverTheScenarios() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int[] counts = new int[3];
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            Random random = new Random(seed);
            try (var f = new JavaTickFixture()) {
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    try {
                        for (PredictionResult candidate : result.getAnyReality()) {
                            compare(scenario, candidate, last[0], random, mismatches, counts);
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    last[0] = result;
                });
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(counts[0] > SCENARIOS * 10, counts[0] + " results");
        assertTrue(counts[1] > 0, counts[1] + " boxes for withheld position reports");
        assertTrue(counts[2] > 0, counts[2] + " traces a handler moved");
    }

    @Test
    void mixedHandlersMatchTheChain() throws Exception {
        PredVector start = new PredVector(new Vec3(0.25, -0.5, 0.125));
        PredictionResult result = new PredictionResult(
                null, start, org.mockito.Mockito.mock(SimulationContext.class), Vec3.ZERO, null, List.of(), List.of());
        UncertaintyHandler nudge = (player, valid, r, context, last, position, end) -> position.add(1, 0, 0, "a");
        UncertaintyHandler same = (player, valid, r, context, last, position, end) -> position;
        UncertaintyHandler rebase = new UncertaintyHandler() {
            @Override
            public MovementTrace handleMovementTrace(
                    CultPlayer player,
                    ValidMovements valid,
                    PredictionResult r,
                    SimulationContext context,
                    PredictionResult last,
                    MovementTrace trace,
                    Vec3 end) {
                return new MovementTrace(
                        trace.position().add(0, 2, 0, "b"),
                        trace.positionOnlyDelta().add(trace.position()),
                        trace.collisionBaseOffset().add(0, 0, 3),
                        trace.preCollisionVelocity(),
                        trace.position());
            }

            @Override
            public PredVector handleUncertainty(
                    CultPlayer player,
                    ValidMovements valid,
                    PredictionResult r,
                    SimulationContext context,
                    PredictionResult last,
                    PredVector position,
                    Vec3 end) {
                throw new AssertionError();
            }
        };
        List<List<UncertaintyHandler>> lists = List.of(
                List.of(same, same),
                List.of(nudge),
                List.of(same, nudge, same),
                List.of(nudge, rebase),
                List.of(rebase, same),
                List.of(nudge, nudge, rebase, nudge, same),
                List.of(same, rebase, same, rebase, nudge));
        for (List<UncertaintyHandler> handlers : lists) {
            ValidMovements valid = new ValidMovements(start, null, result, false, handlers);
            MovementTrace want = chain(valid, handlers, new Vec3(0.5, 0.5, 0.5), null);
            MovementTrace got = valid.transformTraceTowards(new Vec3(0.5, 0.5, 0.5), null);
            assertEquals(describe(want), describe(got), handlers.toString());
            if (want == field(valid, "startTrace")) assertSame(want, got);
        }
    }

    /** Bit for bit but for a NaN's sign and payload, which x86 takes from whichever operand C2 puts first. */
    @Test
    void sphericalCoordinatesMatchTheSphericalOffset() {
        double[] edges = {
            0.0,
            -0.0,
            1.0E-5,
            9.99E-6,
            -1.0E-5,
            0.03,
            -0.03,
            0.06,
            1,
            -1,
            LARGE_MOVEMENT,
            -LARGE_MOVEMENT,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NaN
        };
        double[] radii = {0.0, -0.0, 0.03, 0.0300000001, 1.0E-6, 1, Double.POSITIVE_INFINITY, Double.NaN, -0.03};
        Random random = new Random(7);
        int cases = 0;
        for (int i = 0; i < 200_000; i++) {
            PredVector start = new PredVector(new Vec3(pick(random, edges), pick(random, edges), pick(random, edges)));
            Vec3 end = random.nextInt(4) == 0
                    ? new Vec3(start.x + pick(random, edges), start.y + pick(random, edges), start.z)
                    : new Vec3(pick(random, edges), pick(random, edges), pick(random, edges));
            double radius = random.nextBoolean() ? radii[random.nextInt(radii.length)] : random.nextDouble() * 0.1;
            PredVector want = UncertaintyHelper.handleSpherical(start, end, radius);
            for (int axis = 0; axis < 3; axis++) {
                double got = UncertaintyHelper.sphericalCoordinate(start, end, radius, axis);
                double expected = axis == 0 ? want.x : axis == 1 ? want.y : want.z;
                assertEquals(
                        Double.doubleToLongBits(expected),
                        Double.doubleToLongBits(got),
                        start + " " + end + " " + radius + " axis " + axis);
                cases++;
            }
        }
        assertEquals(600_000, cases);
    }

    private static double pick(Random random, double[] edges) {
        return random.nextInt(3) == 0 ? edges[random.nextInt(edges.length)] : (random.nextDouble() - 0.5) * 4;
    }

    private static void compare(
            long scenario,
            PredictionResult result,
            PredictionResult last,
            Random random,
            List<String> mismatches,
            int[] counts)
            throws ReflectiveOperationException {
        ValidMovements valid = result.getValidMovements();
        if (valid == null) return;
        counts[0]++;
        List<UncertaintyHandler> all = UncertaintyPipeline.MODIFIERS;
        @SuppressWarnings("unchecked")
        List<UncertaintyHandler> handlers = (List<UncertaintyHandler>) field(valid, "uncertaintyHandlers");

        List<Vec3> ends = new ArrayList<>();
        ends.add(result.getTarget());
        ends.add(valid.getClosestToTarget() == null ? Vec3.ZERO : valid.getClosestToTarget());
        for (int i = 0; i < 4; i++) {
            ends.add(result.getTarget()
                    .add(random.nextGaussian() * 0.05, random.nextGaussian() * 0.05, random.nextGaussian() * 0.05));
        }
        PredVector start = (PredVector) field(valid, "initialStartingVel");
        ends.add(start.withX(LARGE_MOVEMENT));
        ends.add(start.withY(-LARGE_MOVEMENT));
        for (Vec3 end : ends) {
            String want = describe(chain(valid, handlers, end, last));
            String got = describe(valid.transformTraceTowards(end, last));
            if (!want.equals(got)) mismatches.add(scenario + " trace " + end + ": " + want + " != " + got);
            if (!got.equals(describe(MovementTrace.start(start)))) counts[2]++;
        }

        String want = boxBits(referenceBox(valid, handlers, all, last));
        String got = boxBits(valid.computeAABB(last));
        if (!want.equals(got)) mismatches.add(scenario + " box: " + want + " != " + got);

        // Withheld reports keep the forward movement and apply their report allowance after collision.
        SimulationContext context = result.getSimulationContext();
        Field withheldPosition = SimulationContext.class.getDeclaredField("withheldPosition");
        withheldPosition.setAccessible(true);
        boolean testing = withheldPosition.getBoolean(context);
        withheldPosition.setBoolean(context, true);
        try {
            for (Vec3 end : ends) {
                String wantTrace = describe(chain(valid, handlers, end, last));
                String gotTrace = describe(valid.transformTraceTowards(end, last));
                if (!wantTrace.equals(gotTrace)) {
                    mismatches.add(scenario + " withheld trace " + end + ": " + wantTrace + " != " + gotTrace);
                }
            }
            want = boxBits(referenceBox(valid, handlers, all, last));
            got = boxBits(valid.computeAABB(last));
            if (!want.equals(got)) mismatches.add(scenario + " withheld box: " + want + " != " + got);
            if (handlers == all) counts[1]++;
        } finally {
            withheldPosition.setBoolean(context, testing);
        }
    }

    /** The trace the chain of every handler's handleMovementTrace returns. */
    private static MovementTrace chain(
            ValidMovements valid, List<UncertaintyHandler> handlers, Vec3 end, PredictionResult last)
            throws ReflectiveOperationException {
        MovementTrace trace = (MovementTrace) field(valid, "startTrace");
        PredictionResult result = valid.getResult();
        SimulationContext context = result.getSimulationContext();
        CultPlayer player = (CultPlayer) field(valid, "player");
        PredVector start = (PredVector) field(valid, "initialStartingVel");
        Vec3 target = end;
        for (UncertaintyHandler handler : handlers) {
            trace = handler.handleMovementTrace(
                    player, result.getValidMovements(), result, context, last, trace, target);
        }
        var pass = trace.passAfterTravel();
        if (pass != null && !context.isWithheldPosition()) {
            var fixed = pass.result();
            Vec3 scale = context.getLastStuckSpeed();
            Vec3 move = fixed.state()
                    .position()
                    .subtract(context.getStart())
                    .multiply(1 / scale.x, 1 / scale.y, 1 / scale.z);
            trace = trace.withPosition(trace.position()
                    .withXYZ(
                            fixed.fixedX() ? move.x : trace.position().x,
                            fixed.fixedY() ? move.y : trace.position().y,
                            fixed.fixedZ() ? move.z : trace.position().z,
                            "block entity fixed axes"));
        }
        return trace;
    }

    private static SimpleCollisionBox referenceBox(
            ValidMovements valid,
            List<UncertaintyHandler> handlers,
            List<UncertaintyHandler> all,
            PredictionResult last)
            throws ReflectiveOperationException {
        List<UncertaintyHandler> kept = handlers;
        if (handlers == all) {
            kept = new ArrayList<>();
            CultPlayer player = (CultPlayer) field(valid, "player");
            SimulationContext context = valid.getResult().getSimulationContext();
            for (UncertaintyHandler handler : handlers) {
                if (!handler.skipsContext(player, context, last)) kept.add(handler);
            }
        }
        PredVector start = (PredVector) field(valid, "initialStartingVel");
        double posX = chain(valid, kept, start.withX(LARGE_MOVEMENT), last).position().x;
        double negX = chain(valid, kept, start.withX(-LARGE_MOVEMENT), last).position().x;
        double posY = chain(valid, kept, start.withY(LARGE_MOVEMENT), last).position().y;
        double negY = chain(valid, kept, start.withY(-LARGE_MOVEMENT), last).position().y;
        double posZ = chain(valid, kept, start.withZ(LARGE_MOVEMENT), last).position().z;
        double negZ = chain(valid, kept, start.withZ(-LARGE_MOVEMENT), last).position().z;
        return new SimpleCollisionBox(negX, negY, negZ, posX, posY, posZ).sort();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static String describe(MovementTrace trace) throws ReflectiveOperationException {
        return lineage(trace.position()) + " delta " + bits(trace.positionOnlyDelta()) + " base "
                + bits(trace.collisionBaseOffset()) + " velocity " + bits(trace.preCollisionVelocity()) + " movement "
                + bits(trace.preCollisionMovement()) + " pass " + trace.passAfterTravel();
    }

    private static String lineage(PredVector vector) throws ReflectiveOperationException {
        Field last = PredVector.class.getDeclaredField("lastVector");
        Field reason = PredVector.class.getDeclaredField("reason");
        last.setAccessible(true);
        reason.setAccessible(true);
        StringBuilder text = new StringBuilder();
        for (PredVector current = vector; current != null; current = (PredVector) last.get(current)) {
            text.append(bits(current)).append(' ').append(reason.get(current)).append(" <- ");
        }
        return text.toString();
    }

    private static String bits(Vec3 vector) {
        if (vector == null) return "null";
        return Long.toHexString(Double.doubleToRawLongBits(vector.x)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(vector.y)) + ","
                + Long.toHexString(Double.doubleToRawLongBits(vector.z));
    }

    private static String boxBits(SimpleCollisionBox box) {
        return Double.doubleToLongBits(box.minX) + " " + Double.doubleToLongBits(box.minY) + " "
                + Double.doubleToLongBits(box.minZ) + " " + Double.doubleToLongBits(box.maxX) + " "
                + Double.doubleToLongBits(box.maxY) + " " + Double.doubleToLongBits(box.maxZ);
    }
}
