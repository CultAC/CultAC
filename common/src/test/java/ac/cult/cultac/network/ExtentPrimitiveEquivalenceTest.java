package ac.cult.cultac.network;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.UncertaintyPipeline;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.data.StuckEdgeData;
import ac.cult.cultac.utils.data.StuckSpeedData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@link ValidMovements#computeAABB} and each handler's positionInto against the chain of handleUncertainty calls on
 * prediction vectors, over the seeded scenarios with every tail handler forced kept and skipped.
 */
class ExtentPrimitiveEquivalenceTest {
    private static final int SCENARIOS = 66;
    private static final int PERTURBATIONS = 12;
    private static final double[] EDGES = {
        0.0,
        -0.0,
        1.0E-5,
        9.99E-6,
        -1.0E-5,
        0.003,
        -0.003,
        0.03,
        -0.03,
        0.56,
        -0.24,
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

    @Test
    void boxesAndFlagsMatchTheHandlerChainWithEveryTailHandlerKeptAndSkipped() throws Exception {
        List<String> mismatches = new ArrayList<>();
        TreeMap<String, Integer> kept = new TreeMap<>();
        int[] boxes = {0};
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            Random random = new Random(seed);
            try (var f = new JavaTickFixture()) {
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    try {
                        for (PredictionResult candidate : result.getAnyReality()) {
                            if (candidate.getValidMovements() == null) continue;
                            List<Integer> masks = new ArrayList<>();
                            masks.add(0);
                            for (int bit = 0; bit < PERTURBATIONS; bit++) masks.add(1 << bit);
                            for (int i = 0; i < 3; i++) masks.add(random.nextInt(1 << PERTURBATIONS));
                            for (int mask : masks) {
                                compareBox(scenario, mask, candidate, last[0], mismatches, kept);
                                boxes[0]++;
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    last[0] = result;
                });
            }
        }
        assertEquals(
                List.of(), mismatches.subList(0, Math.min(20, mismatches.size())), mismatches.size() + " mismatches");
        assertTrue(boxes[0] > SCENARIOS * 100, boxes[0] + " boxes");
        for (String handler : List.of(
                "PointThree",
                "Sneaking",
                "StuckSpeed",
                "BubbleColumn",
                "SwimSpaceShift",
                "AquaticUpdateSwim",
                "FluidPush",
                "InsideBlock",
                "EntityPush",
                "Ladder",
                "PointThreeTesting")) {
            assertTrue(kept.getOrDefault(handler, 0) > 0, handler + " never kept: " + kept);
        }
    }

    @Test
    void positionFormsMatchHandleUncertaintyOnEdgeInputs() throws Exception {
        List<UncertaintyHandler> forms = new ArrayList<>();
        for (UncertaintyHandler handler : UncertaintyPipeline.MODIFIERS) {
            if (declaresPositionForm(handler)) forms.add(handler);
        }
        List<String> names = new ArrayList<>();
        for (UncertaintyHandler handler : forms) names.add(handler.getClass().getSimpleName());
        assertEquals(
                List.of(
                        "FluidPush",
                        "PointThree",
                        "StuckSpeed",
                        "BubbleColumn",
                        "SwimSpaceShift",
                        "FluidPush",
                        "InsideBlock",
                        "EntityPush"),
                names);

        List<String> mismatches = new ArrayList<>();
        int[] counts = new int[3];
        for (long seed = 0; seed < SCENARIOS; seed += 3) {
            long scenario = seed;
            Random random = new Random(seed * 31 + 7);
            try (var f = new JavaTickFixture()) {
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    try {
                        List<PredictionResult> candidates = result.getAnyReality();
                        for (int c = 0; c < Math.min(2, candidates.size()); c++) {
                            PredictionResult candidate = candidates.get(c);
                            if (candidate.getValidMovements() == null) continue;
                            for (int i = 0; i < 24; i++) {
                                int mask = random.nextInt(1 << PERTURBATIONS);
                                PredictionResult lastResult = random.nextInt(16) == 0 ? broken(candidate) : last[0];
                                List<Runnable> undo = perturb(mask, candidate, lastResult);
                                try {
                                    for (UncertaintyHandler handler : forms) {
                                        compareForm(
                                                scenario, handler, candidate, lastResult, random, mismatches, counts);
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
        assertEquals(
                List.of(), mismatches.subList(0, Math.min(20, mismatches.size())), mismatches.size() + " mismatches");
        assertTrue(counts[0] > 50_000, counts[0] + " cases");
        assertTrue(counts[1] > 1_000, counts[1] + " cases a handler moved");
        assertTrue(counts[2] > 100, counts[2] + " cases that threw");
    }

    private static void compareBox(
            long scenario,
            int mask,
            PredictionResult result,
            PredictionResult last,
            List<String> mismatches,
            TreeMap<String, Integer> kept)
            throws ReflectiveOperationException {
        ValidMovements valid = result.getValidMovements();
        boolean vertical = valid.isControlsVerticalMovement();
        boolean skipGravity = valid.isDidTickSkipGravity();
        List<Runnable> undo = perturb(mask, result, last);
        try {
            @SuppressWarnings("unchecked")
            List<UncertaintyHandler> handlers = (List<UncertaintyHandler>) field(valid, "uncertaintyHandlers");
            List<UncertaintyHandler> keptHandlers = keptHandlers(valid, handlers, last);
            if (handlers == UncertaintyPipeline.MODIFIERS) {
                for (UncertaintyHandler handler : keptHandlers) {
                    kept.merge(handler.getClass().getSimpleName(), 1, Integer::sum);
                }
            }
            WorldData world = result.getSimulationContext().getWorldData();

            valid.setControlsVerticalMovement(false);
            valid.setDidTickSkipGravity(false);
            String want = outcome(() -> boxBits(referenceBox(valid, keptHandlers, last)))
                    + flags(valid)
                    + world.getLastOnGround();
            valid.setControlsVerticalMovement(false);
            valid.setDidTickSkipGravity(false);
            String got = outcome(() -> boxBits(valid.computeAABB(last))) + flags(valid) + world.getLastOnGround();
            if (!want.equals(got)) {
                mismatches.add(scenario + " mask " + Integer.toBinaryString(mask) + ": " + want + " != " + got);
            }
        } finally {
            for (int u = undo.size() - 1; u >= 0; u--) undo.get(u).run();
            valid.setControlsVerticalMovement(vertical);
            valid.setDidTickSkipGravity(skipGravity);
        }
    }

    private static void compareForm(
            long scenario,
            UncertaintyHandler handler,
            PredictionResult result,
            PredictionResult last,
            Random random,
            List<String> mismatches,
            int[] counts)
            throws ReflectiveOperationException {
        ValidMovements valid = result.getValidMovements();
        CultPlayer player = (CultPlayer) field(valid, "player");
        SimulationContext context = result.getSimulationContext();
        PredVector initial = (PredVector) field(valid, "initialStartingVel");
        boolean vertical = valid.isControlsVerticalMovement();
        boolean skipGravity = valid.isDidTickSkipGravity();
        boolean tickSkip = initial.isTickSkip();
        boolean entityMotion = initial.isEntityMotion();

        double sx = pick(random), sy = pick(random), sz = pick(random);
        PredVector root = random.nextBoolean() ? initial : new PredVector(new Vec3(pick(random), sy, pick(random)));
        if (random.nextBoolean()) root.setTickSkip(!root.isTickSkip());
        if (random.nextInt(4) == 0) root.setEntityMotion(!root.isEntityMotion());
        try {
            PredVector carrier = random.nextBoolean()
                    ? root
                    : root.withXYZ(pick(random), pick(random), pick(random), VelocityTransformer.SPRINT_JUMP_REASON);
            PredVector start = random.nextInt(4) == 0 ? carrier : carrier.withXYZ(sx, sy, sz, "circular");
            Vec3 end = end(random, start);

            valid.setControlsVerticalMovement(false);
            valid.setDidTickSkipGravity(false);
            String want = outcome(() -> bits(handler.handleUncertainty(
                            player, result.getValidMovements(), result, context, last, start, end)))
                    + flags(valid);
            valid.setControlsVerticalMovement(false);
            valid.setDidTickSkipGravity(false);
            double[] xyz = {start.x, start.y, start.z};
            String got = outcome(() -> {
                        handler.positionInto(
                                xyz, player, result.getValidMovements(), result, context, last, carrier, end);
                        return bits(xyz[0], xyz[1], xyz[2]);
                    })
                    + flags(valid);
            counts[0]++;
            if (!want.startsWith(bits(start.x, start.y, start.z))) counts[1]++;
            if (want.startsWith("threw")) counts[2]++;
            if (!want.equals(got)) {
                mismatches.add(scenario + " " + handler.getClass().getSimpleName() + " " + start + " -> " + end + ": "
                        + want + " != " + got);
            }
        } finally {
            initial.setTickSkip(tickSkip);
            initial.setEntityMotion(entityMotion);
            valid.setControlsVerticalMovement(vertical);
            valid.setDidTickSkipGravity(skipGravity);
        }
    }

    /** Forces the state that keeps one tail handler per bit of {@code mask}; the runnables restore it in reverse. */
    static List<Runnable> perturb(int mask, PredictionResult result, PredictionResult last)
            throws ReflectiveOperationException {
        List<Runnable> undo = new ArrayList<>();
        SimulationContext context = result.getSimulationContext();
        WorldData world = context.getWorldData();
        WorldData lastWorld = last == null || last.getSimulationContext() == null
                ? null
                : last.getSimulationContext().getWorldData();
        PredVector initial = (PredVector) field(result.getValidMovements(), "initialStartingVel");
        if ((mask & 1) != 0) set(undo, world, "mightBeInBlock", true);
        if ((mask & 1 << 1) != 0) set(undo, world, "inFlowingLiquid", DesyncStatus.TRUE);
        if ((mask & 1 << 2) != 0) set(undo, world, "inWater", DesyncStatus.TRUE);
        if ((mask & 1 << 3) != 0) {
            set(undo, world, "loadingLevelPushes", new SimpleCollisionBox(-0.07, 0, -0.05, 0.06, 0, 0.08));
            set(undo, world, "numPushing", world.getNumPushing() + 2);
        }
        if ((mask & 1 << 4) != 0) {
            set(undo, world, "numCarriedPushing", 1);
            set(undo, world, "numPushing", Math.max(world.getNumPushing(), 1) + 1);
            set(undo, world, "carriedPush", new CarriedPush(0.0, 0.0, 1.0, -1.0, 0.0, 0.0));
        }
        if ((mask & 1 << 5) != 0 && lastWorld != null) {
            set(undo, lastWorld, "bubbleColumn", new BubbleColumnData(1, 1, 1, 1));
        }
        if ((mask & 1 << 6) != 0) {
            set(undo, context, "version", ClientVersion.V_1_21_4);
            if (lastWorld != null) {
                StuckSpeedData stuck = lastWorld.getStuckSpeed();
                set(
                        undo,
                        lastWorld,
                        "stuckSpeed",
                        new StuckSpeedData(
                                stuck == null ? null : stuck.getStuckSpeedMultiplier(), new Vec3(0.25, 0.05, 0.25)));
            }
        }
        if ((mask & 1 << 7) != 0) {
            boolean old = initial.isTickSkip();
            initial.setTickSkip(true);
            undo.add(() -> initial.setTickSkip(old));
        }
        if ((mask & 1 << 8) != 0) {
            boolean old = initial.isEntityMotion();
            initial.setEntityMotion(true);
            undo.add(() -> initial.setEntityMotion(old));
        }
        if ((mask & 1 << 9) != 0) set(undo, context, "isTestingPointThree", true);
        if ((mask & 1 << 10) != 0) set(undo, world, "sneak", new StuckEdgeData(true, false, false, true, false));
        if ((mask & 1 << 11) != 0) set(undo, world, "climbingAtStart", DesyncStatus.TRUE);
        return undo;
    }

    static List<UncertaintyHandler> keptHandlers(
            ValidMovements valid, List<UncertaintyHandler> handlers, PredictionResult last)
            throws ReflectiveOperationException {
        if (handlers != UncertaintyPipeline.MODIFIERS) return handlers;
        List<UncertaintyHandler> kept = new ArrayList<>();
        CultPlayer player = (CultPlayer) field(valid, "player");
        SimulationContext context = valid.getResult().getSimulationContext();
        for (UncertaintyHandler handler : handlers) {
            if (!handler.skipsContext(player, context, last)) kept.add(handler);
        }
        return kept;
    }

    /** The box of the six extents as the chain of every kept handler's handleMovementTrace moves them. */
    static SimpleCollisionBox referenceBox(ValidMovements valid, List<UncertaintyHandler> kept, PredictionResult last)
            throws ReflectiveOperationException {
        PredVector start = (PredVector) field(valid, "initialStartingVel");
        double posX = chain(valid, kept, start.withX(LARGE_MOVEMENT), last).position().x;
        double negX = chain(valid, kept, start.withX(-LARGE_MOVEMENT), last).position().x;
        double posY = chain(valid, kept, start.withY(LARGE_MOVEMENT), last).position().y;
        double negY = chain(valid, kept, start.withY(-LARGE_MOVEMENT), last).position().y;
        double posZ = chain(valid, kept, start.withZ(LARGE_MOVEMENT), last).position().z;
        double negZ = chain(valid, kept, start.withZ(-LARGE_MOVEMENT), last).position().z;
        return new SimpleCollisionBox(negX, negY, negZ, posX, posY, posZ).sort();
    }

    private static MovementTrace chain(
            ValidMovements valid, List<UncertaintyHandler> handlers, Vec3 end, PredictionResult last)
            throws ReflectiveOperationException {
        MovementTrace trace = (MovementTrace) field(valid, "startTrace");
        PredictionResult result = valid.getResult();
        SimulationContext context = result.getSimulationContext();
        CultPlayer player = (CultPlayer) field(valid, "player");
        for (UncertaintyHandler handler : handlers) {
            trace = handler.handleMovementTrace(player, result.getValidMovements(), result, context, last, trace, end);
        }
        return trace;
    }

    static boolean declaresPositionForm(UncertaintyHandler handler) throws NoSuchMethodException {
        Method method = handler.getClass()
                .getMethod(
                        "positionInto",
                        double[].class,
                        CultPlayer.class,
                        ValidMovements.class,
                        PredictionResult.class,
                        SimulationContext.class,
                        PredictionResult.class,
                        PredVector.class,
                        double.class,
                        double.class,
                        double.class);
        return method.getDeclaringClass() != UncertaintyHandler.class;
    }

    /** A last result without a context, which every handler that reads the last context fails on. */
    private static PredictionResult broken(PredictionResult result) {
        return new PredictionResult(null, result.getInitialStartingVel(), null, Vec3.ZERO, null, List.of(), List.of());
    }

    private static Vec3 end(Random random, PredVector start) {
        return switch (random.nextInt(5)) {
            case 0 -> start.withX(random.nextBoolean() ? LARGE_MOVEMENT : -LARGE_MOVEMENT);
            case 1 -> start.withY(random.nextBoolean() ? LARGE_MOVEMENT : -LARGE_MOVEMENT);
            case 2 -> start.withZ(random.nextBoolean() ? LARGE_MOVEMENT : -LARGE_MOVEMENT);
            case 3 -> new Vec3(start.x + pick(random) * 0.01, start.y + pick(random) * 0.01, start.z);
            default -> new Vec3(pick(random), pick(random), pick(random));
        };
    }

    private static double pick(Random random) {
        return random.nextInt(3) == 0 ? EDGES[random.nextInt(EDGES.length)] : (random.nextDouble() - 0.5) * 2;
    }

    interface Outcome {
        String get() throws Exception;
    }

    static String outcome(Outcome outcome) {
        try {
            return outcome.get();
        } catch (Exception | AssertionError e) {
            return "threw " + e.getClass().getName();
        }
    }

    static String flags(ValidMovements valid) {
        return " vertical " + valid.isControlsVerticalMovement() + " gravity " + valid.isDidTickSkipGravity() + " ";
    }

    private static void set(List<Runnable> undo, Object owner, String name, Object value)
            throws ReflectiveOperationException {
        Field field = declared(owner.getClass(), name);
        field.setAccessible(true);
        Object old = field.get(owner);
        field.set(owner, value);
        undo.add(() -> {
            try {
                field.set(owner, old);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static Field declared(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Declared on a superclass.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = declared(owner.getClass(), name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static String bits(Vec3 vector) {
        return bits(vector.x, vector.y, vector.z);
    }

    /** Raw bits but for a NaN's sign and payload, which x86 takes from whichever operand C2 puts first. */
    private static String bits(double x, double y, double z) {
        return bits(x) + "," + bits(y) + "," + bits(z);
    }

    private static String bits(double value) {
        return Double.isNaN(value) ? "NaN" : Long.toHexString(Double.doubleToRawLongBits(value));
    }

    static String boxBits(SimpleCollisionBox box) {
        return bits(box.minX, box.minY, box.minZ) + " " + bits(box.maxX, box.maxY, box.maxZ);
    }
}
