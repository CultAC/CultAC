package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.Components;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.impl.prediction.checks.NoFall;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemo;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemTypes;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The step proof VerticalAnalyzer leaves on a result is the one NoFall would prove itself. */
class StepProofMemoEquivalenceTest {
    private static final int SCENARIOS = 66;

    @Test
    void noFallSeesTheProofItWouldCompute() throws Exception {
        Class<?> type = Class.forName("ac.cult.cultac.checks.impl.prediction.checks.VanillaStepProof");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object proof = constructor.newInstance();
        Method prove = type.getDeclaredMethod("proveSourceStep", CultPlayer.class, PredictionResult.class);
        prove.setAccessible(true);
        Field open = StepCollisionMemo.class.getDeclaredField("open");
        open.setAccessible(true);
        int[] shared = {0};
        List<String> mismatches = new ArrayList<>();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                StepCollisionMemo collisionMemo =
                        f.player.checkManager.getSimulationProcessor().getStepCollisionMemo();
                List<EngineCheck> checks = checks(f);
                int index = -1;
                for (int i = 0; i < checks.size(); i++) if (checks.get(i) instanceof NoFall) index = i;
                EngineCheck noFall = checks.get(index);
                checks.set(index, (player, result, lastResult) -> {
                    Boolean memo = result.getSourceProvenStep();
                    if (memo != null) {
                        shared[0]++;
                        boolean fresh;
                        try {
                            boolean wasOpen = open.getBoolean(collisionMemo);
                            open.setBoolean(collisionMemo, false);
                            try {
                                fresh = (boolean) prove.invoke(proof, player, result);
                            } finally {
                                open.setBoolean(collisionMemo, wasOpen);
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                        if (fresh != memo) mismatches.add(scenario + ": " + memo + " != " + fresh);
                    }
                    noFall.handleResult(player, result, lastResult);
                });
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(shared[0] > SCENARIOS, shared[0] + " shared proofs");
    }

    /**
     * Sneaking with leather boots, a powder snow fall distance and the step attribute are collision inputs outside the
     * memo key; each changes the answer between two predictions, and the second sees its own.
     */
    @Test
    void inputsOutsideTheKeyDoNotSurviveAPrediction() throws Exception {
        Method stepped = StepCollisionMemo.class.getDeclaredMethod(
                "steppedCollision",
                CultPlayer.class,
                SimpleCollisionBox.class,
                double.class,
                double.class,
                double.class,
                List.class,
                boolean.class);
        stepped.setAccessible(true);
        Method below = StepCollisionMemo.class.getDeclaredMethod(
                "collidesDirectlyBelow", CultPlayer.class, SimpleCollisionBox.class);
        below.setAccessible(true);
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            SimulationProcessor processor = player.checkManager.getSimulationProcessor();
            StepCollisionMemo memo = processor.getStepCollisionMemo();
            int floor = JavaTickFixture.FLOOR_Y;
            player.compensatedWorld.updateBlock(new BlockPos(0, floor - 1, 0), BlockIds.POWDER_SNOW.defaultState());
            player.compensatedWorld.updateBlock(new BlockPos(1, floor, 0), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);

            SimpleCollisionBox onSnow = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, floor, 0.5, 0.6F, 1.8F);
            SimpleCollisionBox inSnow = GetBoundingBox.getBoundingBoxFromPosAndSize(0.5, floor - 0.05, 0.5, 0.6F, 1.8F);
            List<Collisions.Axis> order = List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z);
            Supplier<Object> belowOnSnow = () -> invoke(below, memo, player, onSnow);
            Supplier<Object> fallIntoSnow = () -> invoke(stepped, memo, player, inSnow, 0.0, -0.1, 0.0, order, false);
            Supplier<Object> stepIntoWall = () -> invoke(stepped, memo, player, onSnow, 0.5, -0.0784, 0.0, order, true);

            boots(player, new SimItemStack(ItemTypes.LEATHER_BOOTS, 1, Components.EMPTY));
            assertChangesBetweenPredictions(memo, belowOnSnow, () -> player.isSneaking = true, "sneaking");
            player.isSneaking = false;
            boots(player, SimItemStack.EMPTY);

            Field carry = SimulationProcessor.class.getDeclaredField("profileCarry");
            carry.setAccessible(true);
            PacketEntity actor = player.compensatedEntities.getEntityInControl();
            assertChangesBetweenPredictions(
                    memo,
                    fallIntoSnow,
                    () -> set(carry, processor, new JavaPredictionCarry(actor, 3.0)),
                    "fall distance");
            set(carry, processor, null);

            assertChangesBetweenPredictions(
                    memo,
                    stepIntoWall,
                    () -> player.compensatedEntities.getSelf().stepHeightAttribute = 1.5,
                    "step attribute");
        }
    }

    private static void boots(CultPlayer player, SimItemStack boots) {
        player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_BOOTS, boots);
    }

    private static void assertChangesBetweenPredictions(
            StepCollisionMemo memo, Supplier<Object> query, Runnable change, String input) {
        memo.open(true);
        Object first = query.get();
        assertEquals(first, query.get(), input + ": the memo answers alike within a prediction");
        memo.close();
        change.run();
        Object fresh = query.get();
        memo.open(true);
        Object second = query.get();
        memo.close();
        assertNotEquals(first, fresh, input + " does not change the collision");
        assertEquals(fresh, second, input + " leaked from the previous prediction");
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            Object value = method.invoke(target, args);
            return value instanceof Collisions.SteppedCollision c
                    ? List.of(c.movement().x, c.movement().y, c.movement().z, c.withoutStep().x, c.withoutStep().y)
                    : value;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EngineCheck> checks(JavaTickFixture f) throws Exception {
        Field flagger = SimulationProcessor.class.getDeclaredField("flagger");
        flagger.setAccessible(true);
        Object runner = flagger.get(f.player.checkManager.getSimulationProcessor());
        Field checks = PredCheckRunner.class.getDeclaredField("checks");
        checks.setAccessible(true);
        return (List<EngineCheck>) checks.get(runner);
    }
}
