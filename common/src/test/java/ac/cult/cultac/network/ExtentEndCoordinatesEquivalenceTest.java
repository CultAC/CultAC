package ac.cult.cultac.network;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.PointThreeTesting;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.StepTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link ValidMovements#computeAABB} against the chain of every kept handler's handleMovementTrace on the six end
 * vectors, over the seeded scenarios' candidates and over starts, step states and broken results built from them. The
 * starts include signed zeros, NaN, infinities, huge values and starts that make the box zero-sized.
 */
class ExtentEndCoordinatesEquivalenceTest {
    private static final int SCENARIOS = 66;
    private static final double[] EDGES = {
        0.0,
        -0.0,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        1.0E-5,
        -1.0E-5,
        0.003,
        -0.03,
        0.42,
        -0.0784,
        LARGE_MOVEMENT,
        -LARGE_MOVEMENT,
        Math.nextUp(LARGE_MOVEMENT),
        1.0E15,
        -3.0E7,
        Double.MAX_VALUE,
        -Double.MAX_VALUE,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NaN,
        Double.longBitsToDouble(0xFFF8000000000001L)
    };

    @Test
    void boxesAndFlagsMatchTheHandlerChainOnScenarioAndEdgeStarts() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int[] counts = new int[7];
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            Random random = new Random(seed * 131 + 17);
            try (var f = new JavaTickFixture()) {
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    try {
                        for (PredictionResult candidate : result.getAnyReality()) {
                            if (candidate.getValidMovements() == null) continue;
                            int[] masks = {0, random.nextInt(1 << 12), random.nextInt(1 << 12)};
                            for (int mask : masks) {
                                List<Runnable> undo = ExtentPrimitiveEquivalenceTest.perturb(mask, candidate, last[0]);
                                try {
                                    compare(
                                            scenario,
                                            "candidate",
                                            candidate.getValidMovements(),
                                            last[0],
                                            mismatches,
                                            counts);
                                    if (mask == 0) {
                                        counts[5]++;
                                        if (onCoordinates(
                                                candidate.getValidMovements(), UncertaintyPipeline.MODIFIERS, last[0]))
                                            counts[6]++;
                                    }
                                    for (int i = 0; i < 4; i++) {
                                        compare(
                                                scenario,
                                                "edge",
                                                edgeMovements(candidate, random),
                                                last[0],
                                                mismatches,
                                                counts);
                                    }
                                } finally {
                                    for (int u = undo.size() - 1; u >= 0; u--)
                                        undo.get(u).run();
                                }
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    last[0] = result;
                });
            }
        }
        System.out.printf(
                "extent end coordinates: %d boxes, %d on coordinates, %d on end vectors, %d threw, %d with NaN;"
                        + " unperturbed candidates %d, %d on coordinates%n",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6]);
        assertEquals(
                List.of(), mismatches.subList(0, Math.min(20, mismatches.size())), mismatches.size() + " mismatches");
        assertTrue(counts[1] > SCENARIOS * 100, counts[1] + " boxes on coordinates");
        assertTrue(counts[2] > SCENARIOS * 10, counts[2] + " boxes on end vectors");
        assertTrue(counts[3] > 10, counts[3] + " boxes that threw");
        assertTrue(counts[4] > 100, counts[4] + " boxes with NaN");
    }

    private static void compare(
            long scenario,
            String kind,
            ValidMovements valid,
            PredictionResult last,
            List<String> mismatches,
            int[] counts)
            throws ReflectiveOperationException {
        boolean vertical = valid.isControlsVerticalMovement();
        boolean skipGravity = valid.isDidTickSkipGravity();
        try {
            @SuppressWarnings("unchecked")
            List<UncertaintyHandler> handlers =
                    (List<UncertaintyHandler>) ExtentPrimitiveEquivalenceTest.field(valid, "uncertaintyHandlers");
            boolean coordinates = onCoordinates(valid, handlers, last);
            String want;
            try {
                List<UncertaintyHandler> kept = ExtentPrimitiveEquivalenceTest.keptHandlers(valid, handlers, last);
                valid.setControlsVerticalMovement(false);
                valid.setDidTickSkipGravity(false);
                want = ExtentPrimitiveEquivalenceTest.outcome(
                                () -> rawBits(ExtentPrimitiveEquivalenceTest.referenceBox(valid, kept, last)))
                        + ExtentPrimitiveEquivalenceTest.flags(valid);
            } catch (RuntimeException e) {
                want = "threw " + e.getClass().getName() + ExtentPrimitiveEquivalenceTest.flags(valid);
            }
            valid.setControlsVerticalMovement(false);
            valid.setDidTickSkipGravity(false);
            String got = ExtentPrimitiveEquivalenceTest.outcome(() -> rawBits(valid.computeAABB(last)))
                    + ExtentPrimitiveEquivalenceTest.flags(valid);
            counts[0]++;
            counts[coordinates ? 1 : 2]++;
            if (want.startsWith("threw")) counts[3]++;
            if (want.contains("NaN")) counts[4]++;
            if (!want.equals(got)) {
                mismatches.add(scenario + " " + kind + (coordinates ? " coordinates " : " vectors ")
                        + ExtentPrimitiveEquivalenceTest.field(valid, "initialStartingVel") + ": " + want + " != "
                        + got);
            }
        } finally {
            valid.setControlsVerticalMovement(vertical);
            valid.setDidTickSkipGravity(skipGravity);
        }
    }

    /** A fresh candidate on the scenario's context from an edge start with the candidate's flags. */
    private static ValidMovements edgeMovements(PredictionResult candidate, Random random)
            throws ReflectiveOperationException {
        ValidMovements valid = candidate.getValidMovements();
        CultPlayer player = (CultPlayer) ExtentPrimitiveEquivalenceTest.field(valid, "player");
        PredVector initial = (PredVector) ExtentPrimitiveEquivalenceTest.field(valid, "initialStartingVel");
        PredVector start = switch (random.nextInt(4)) {
            case 0 -> initial;
            case 1 -> initial.withXYZ(initial.x, pick(random), initial.z, "edge");
            default -> initial.withXYZ(pick(random), pick(random), pick(random), "edge");
        };
        SimulationContext context = candidate.getSimulationContext();
        PredictionResult result = new PredictionResult(
                player, start, context, candidate.getTarget(), null, new ArrayList<>(), new ArrayList<>());
        result.setCollideAxisData(candidate.getCollideAxisData());
        ValidMovements edge = new ValidMovements(start, random.nextInt(32) == 0 ? null : player, result, false);
        edge.setCanStep(random.nextInt(3) == 0);
        if (random.nextInt(24) == 0) result.setValidMovements(null);
        return edge;
    }

    /** Whether computeAABB runs the extents on the end coordinates, as its kept mask and step state decide. */
    private static boolean onCoordinates(ValidMovements valid, List<UncertaintyHandler> handlers, PredictionResult last)
            throws ReflectiveOperationException {
        if (handlers != UncertaintyPipeline.MODIFIERS) return false;
        CultPlayer player = (CultPlayer) ExtentPrimitiveEquivalenceTest.field(valid, "player");
        ValidMovements current = valid.getResult().getValidMovements();
        if (player == null || current == null || current.isCanStep()) return false;
        SimulationContext context = valid.getResult().getSimulationContext();
        int count = handlers.size();
        boolean[] kept = new boolean[count];
        for (int i = 0; i < count; i++) kept[i] = !handlers.get(i).skipsContext(player, context, last);
        if (handlers.get(count - 1) instanceof PointThreeTesting && kept[count - 1]) count--;
        int tail = count;
        while (tail > 0
                && (!kept[tail - 1] || ExtentPrimitiveEquivalenceTest.declaresPositionForm(handlers.get(tail - 1))))
            tail--;
        for (int i = 0; i < tail; i++) {
            Class<?> type = handlers.get(i).getClass();
            if (kept[i] && type != CollisionModifier.class && type != StepTransform.class) return false;
        }
        return true;
    }

    private static double pick(Random random) {
        return random.nextInt(2) == 0 ? EDGES[random.nextInt(EDGES.length)] : (random.nextDouble() - 0.5) * 4;
    }

    /** Raw bits but for a NaN's sign and payload, which x86 takes from whichever operand C2 puts first. */
    private static String rawBits(SimpleCollisionBox box) {
        return bits(box.minX) + "," + bits(box.minY) + "," + bits(box.minZ) + " " + bits(box.maxX) + ","
                + bits(box.maxY) + "," + bits(box.maxZ);
    }

    private static String bits(double value) {
        return Double.isNaN(value) ? "NaN" : Long.toHexString(Double.doubleToRawLongBits(value));
    }
}
