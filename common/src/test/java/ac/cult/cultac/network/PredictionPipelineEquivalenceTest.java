package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SimulationContext.HardCollidingEntityCollision;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CheckingCollisionProbe;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldStageBuilder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BoatData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

/** The prediction pipeline's shortcuts against what they replace, over the seeded {@link PredictionScenarios}. */
class PredictionPipelineEquivalenceTest {
    private static final int SCENARIOS = 66;

    /** A probe reusing resolved axis steps returns, bit for bit, what the probe as of 515d08404 returned. */
    @Test
    void probesMatchTheProbeWithoutStepReuse() throws Exception {
        int probes = 0;
        int grounded = 0;
        List<String> mismatches = new ArrayList<>();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            try (var f = new JavaTickFixture()) {
                CheckingCollisionProbe probe = new CheckingCollisionProbe(PredictionDigestTest::axes);
                swap(f, "collisionProbe", probe);
                PredictionScenarios.run(f, seed, result -> {});
                probes += probe.probes;
                grounded += probe.groundedProbes;
                for (String mismatch : probe.mismatches) mismatches.add(seed + ": " + mismatch);
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(grounded > 100 && probes > grounded, probes + " probes, " + grounded + " grounded");
    }

    /** Carry states share one world build, so a second build for the same context must give the same world. */
    @Test
    void aWorldBuiltTwiceForOneContextIsTheSame() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int[] builds = {0};
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                swap(f, "worldStageBuilder", new WorldStageBuilder() {
                    @Override
                    public WorldData generateWorldData(
                            CultPlayer player,
                            SimulationContext context,
                            PredictionResult lastPrediction,
                            DesyncStatus lastOnGround) {
                        WorldData first = super.generateWorldData(player, context, lastPrediction, lastOnGround);
                        String firstBoxes = boxes(context);
                        WorldData second = super.generateWorldData(player, context, lastPrediction, lastOnGround);
                        builds[0]++;
                        String want = fields(first) + firstBoxes;
                        String got = fields(second) + boxes(context);
                        if (!want.equals(got)) mismatches.add(scenario + ": " + want + " != " + got);
                        return second;
                    }
                });
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(builds[0] > SCENARIOS * 10, builds[0] + " builds");
    }

    /** Each carry context's own world build matches, field by field, the shared world it was handed. */
    @Test
    void everyCarryContextGetsTheWorldItWouldBuild() throws Exception {
        List<String> mismatches = new ArrayList<>();
        int[] shared = {0};
        StackWalker walker = StackWalker.getInstance();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                Object[] inputs = new Object[3];
                swap(f, "worldStageBuilder", new WorldStageBuilder() {
                    @Override
                    public WorldData generateWorldData(
                            CultPlayer player,
                            SimulationContext context,
                            PredictionResult lastPrediction,
                            DesyncStatus lastOnGround) {
                        inputs[0] = lastPrediction;
                        inputs[1] = lastOnGround;
                        inputs[2] = context;
                        return super.generateWorldData(player, context, lastPrediction, lastOnGround);
                    }
                });
                f.player.boatData = new BoatData() {
                    @Override
                    public Vec3 adjustStuckSpeedMultiplierForCurrentTick(
                            CultPlayer player, SimulationContext context, Vec3 multiplier) {
                        boolean carry = walker.walk(frames -> frames.skip(1)
                                .findFirst()
                                .map(frame -> frame.getMethodName().equals("createSimulationContexts"))
                                .orElse(false));
                        if (carry) {
                            WorldData attached = context.getWorldData();
                            List<SimpleCollisionBox> boxes = context.getEntityCollisionBoxesForMovementTick();
                            List<HardCollidingEntityCollision> hard =
                                    context.getHardCollidingEntityCollisionsForMovementTick();
                            String got = fields(attached) + boxes(context);
                            context.cacheEntityCollisionBoxesForMovementTick(null);
                            context.cacheHardCollidingEntityCollisionsForMovementTick(null);
                            WorldData own = new WorldStageBuilder()
                                    .generateWorldData(
                                            player, context, (PredictionResult) inputs[0], (DesyncStatus) inputs[1]);
                            String want = fields(own) + boxes(context);
                            if (context != inputs[2]) shared[0]++;
                            if (!want.equals(got)) mismatches.add(scenario + ": " + want + " != " + got);
                            context.setWorldData(attached);
                            context.cacheEntityCollisionBoxesForMovementTick(boxes);
                            context.cacheHardCollidingEntityCollisionsForMovementTick(hard);
                        }
                        return super.adjustStuckSpeedMultiplierForCurrentTick(player, context, multiplier);
                    }
                };
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(shared[0] > SCENARIOS * 10, shared[0] + " carry contexts");
    }

    /** One field at a time set apart from its same-typed siblings: the copy carries each to the same field. */
    @Test
    void aWorldCopyCarriesEveryFieldToItself() throws Exception {
        Constructor<?> constructor = WorldData.class.getDeclaredConstructors()[0];
        List<Field> fields = instanceFields();
        assertEquals(fields.size(), constructor.getParameterCount());
        for (int marked = 0; marked < fields.size(); marked++) {
            Object[] args = new Object[fields.size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = i == marked
                        ? marker(fields.get(i).getType())
                        : plain(fields.get(i).getType());
            }
            WorldData world = (WorldData) constructor.newInstance(args);
            WorldData copy = world.copy();
            for (Field field : fields) {
                Object want = field.get(world);
                Object got = field.get(copy);
                if (field.getType().isPrimitive()) {
                    assertEquals(want, got, field.getName());
                } else {
                    assertSame(want, got, field.getName());
                }
            }
            assertEquals(world.toString(), copy.toString());
        }
    }

    /** A handler list other than the shared pipeline runs in its own order, each handler once. */
    @Test
    void aCustomHandlerListRunsInItsOrder() {
        List<Integer> calls = new ArrayList<>();
        List<UncertaintyHandler> handlers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int index = i;
            handlers.add((player, valid, result, context, last, start, end) -> {
                calls.add(index);
                return start.withX(start.x + index, "handler " + index);
            });
        }
        PredVector start = new PredVector(new Vec3(0.25, 0, 0));
        PredictionResult result = new PredictionResult(null, start, null, Vec3.ZERO, null, List.of(), List.of());
        ValidMovements valid = new ValidMovements(start, null, result, false, List.copyOf(handlers));
        handlers.clear();

        Vec3 moved = valid.transformTowards(Vec3.ZERO, null);

        assertEquals(List.of(0, 1, 2, 3, 4), calls);
        assertEquals(10.25, moved.x);
    }

    private static void swap(JavaTickFixture f, String name, Object value) throws Exception {
        Field field = SimulationProcessor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(f.player.checkManager.getSimulationProcessor(), value);
    }

    private static String boxes(SimulationContext context) {
        return " boxes " + context.getEntityCollisionBoxesForMovementTick() + " hard "
                + context.getHardCollidingEntityCollisionsForMovementTick();
    }

    private static List<Field> instanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : WorldData.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            fields.add(field);
        }
        return fields;
    }

    /** Each field by raw bits or by its own toString. */
    private static String fields(WorldData world) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field field : instanceFields()) {
                Object value = field.get(world);
                sb.append(field.getName()).append('=');
                if (value instanceof Double d) {
                    sb.append(PredictionDigestTest.bits(d));
                } else {
                    sb.append(value);
                }
                sb.append(' ');
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }

    private static Object plain(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == DesyncStatus.class) return DesyncStatus.FALSE;
        assertTrue(!type.isPrimitive(), type.getName());
        return null;
    }

    private static Object marker(Class<?> type) {
        if (type == boolean.class) return true;
        if (type == int.class) return 7;
        if (type == DesyncStatus.class) return DesyncStatus.TRUE;
        return new ObjenesisStd().newInstance(type);
    }
}
