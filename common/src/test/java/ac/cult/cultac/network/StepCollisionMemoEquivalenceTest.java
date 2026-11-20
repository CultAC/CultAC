package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.checks.EngineCheck;
import ac.cult.cultac.checks.impl.prediction.checks.NoFall;
import ac.cult.cultac.checks.impl.prediction.checks.PredCheckRunner;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemo;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.events.packets.PacketWorldBorder;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Every memoised step-proof collision equals a fresh one computed later in the same prediction. */
class StepCollisionMemoEquivalenceTest {
    private static final int SCENARIOS = 66;
    private static final long STEP = 1L;
    private static final long Z_FIRST = 4L;
    private static final long ON_GROUND = 8L;
    private static final long FULL_BLOCK = 16L;

    @Test
    void memoisedCollisionsMatchFreshOnesAtEveryLaterCheck() throws Exception {
        Field keys = field("keys");
        Field values = field("values");
        Field size = field("size");
        Field open = field("open");
        int[] verified = {0};
        int[] entries = {0};
        List<String> mismatches = new ArrayList<>();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
                StepCollisionMemo memo = processor.getStepCollisionMemo();
                List<EngineCheck> checks = checks(processor);
                int index = -1;
                for (int i = 0; i < checks.size(); i++) if (checks.get(i) instanceof NoFall) index = i;
                EngineCheck noFall = checks.get(index);
                checks.set(index, (player, result, lastResult) -> {
                    noFall.handleResult(player, result, lastResult);
                    try {
                        int count = size.getInt(memo);
                        entries[0] = Math.max(entries[0], count);
                        long[] k = (long[]) keys.get(memo);
                        Object[] v = (Object[]) values.get(memo);
                        for (int e = 0; e < count; e++) {
                            String mismatch = verify(player, k, e * 10, v[e]);
                            verified[0]++;
                            if (mismatch != null) mismatches.add(scenario + ": " + mismatch);
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });
                PredictionScenarios.run(f, seed, result -> {
                    try {
                        if (size.getInt(memo) != 0 || open.getBoolean(memo)) {
                            mismatches.add(scenario + ": the memo outlived its prediction");
                        }
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(verified[0] > SCENARIOS, verified[0] + " verified entries");
        System.out.println("verified " + verified[0] + " memo entries, at most " + entries[0] + " per prediction");
    }

    /** The memo stays closed while a time-based border lerp makes each collision read the clock. */
    @Test
    void aTimeBasedBorderLerpKeepsTheMemoClosed() throws Exception {
        assertEquals(Set.of(true), openStates(border -> {}));
        long now = System.currentTimeMillis();
        assertEquals(Set.of(false), openStates(border -> {
            setField(border, "tickBasedLerp", false);
            setField(border, "oldDiameter", 1_000.0D);
            setField(border, "newDiameter", 2_000.0D);
            setField(border, "startTime", now);
            setField(border, "endTime", now + 600_000L);
        }));
        assertEquals(Set.of(true), openStates(border -> {
            setField(border, "tickBasedLerp", true);
            setField(border, "oldDiameter", 1_000.0D);
            setField(border, "newDiameter", 2_000.0D);
            setField(border, "lerpDurationTicks", 1_000L);
            setField(border, "lerpElapsedTicks", 1L);
        }));
    }

    private static Set<Boolean> openStates(Consumer<PacketWorldBorder> border) throws Exception {
        Field open = field("open");
        Set<Boolean> states = new TreeSet<>();
        for (long seed = 0; seed < 8; seed++) {
            try (var f = new JavaTickFixture()) {
                border.accept(f.player.checkManager.getWorldBorder());
                SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
                StepCollisionMemo memo = processor.getStepCollisionMemo();
                List<EngineCheck> checks = checks(processor);
                for (int i = 0; i < checks.size(); i++) {
                    if (!(checks.get(i) instanceof NoFall)) continue;
                    EngineCheck noFall = checks.get(i);
                    checks.set(i, (player, result, lastResult) -> {
                        try {
                            states.add(open.getBoolean(memo));
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                        noFall.handleResult(player, result, lastResult);
                    });
                }
                PredictionScenarios.run(f, seed, result -> {});
            }
        }
        return states;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String verify(CultPlayer player, long[] k, int at, Object memoised) {
        long tag = k[at];
        SimpleCollisionBox box = new SimpleCollisionBox(
                d(k[at + 1]),
                d(k[at + 2]),
                d(k[at + 3]),
                d(k[at + 4]),
                d(k[at + 5]),
                d(k[at + 6]),
                (tag & FULL_BLOCK) != 0);
        boolean onGround = (tag & ON_GROUND) != 0;
        if ((tag & STEP) != 0) {
            List<Collisions.Axis> order = (tag & Z_FIRST) != 0
                    ? List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X)
                    : List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z);
            Collisions.SteppedCollision fresh = Collisions.collideWithStepComparison(
                    player, box, d(k[at + 7]), d(k[at + 8]), d(k[at + 9]), order, Collections.emptyList(), onGround);
            Collisions.SteppedCollision cached = (Collisions.SteppedCollision) memoised;
            if (same(fresh.movement(), cached.movement()) && same(fresh.withoutStep(), cached.withoutStep())) {
                return null;
            }
            return "step " + box + " " + cached + " != " + fresh;
        }
        boolean saved = player.lastOnGround;
        player.lastOnGround = onGround;
        try {
            boolean fresh = Collisions.collide(player, box, 0.0D, -SimpleCollisionBox.COLLISION_EPSILON, 0.0D).y
                    != -SimpleCollisionBox.COLLISION_EPSILON;
            return fresh == (Boolean) memoised ? null : "below " + box + " " + memoised + " != " + fresh;
        } finally {
            player.lastOnGround = saved;
        }
    }

    private static boolean same(Vec3 a, Vec3 b) {
        return Double.doubleToRawLongBits(a.x) == Double.doubleToRawLongBits(b.x)
                && Double.doubleToRawLongBits(a.y) == Double.doubleToRawLongBits(b.y)
                && Double.doubleToRawLongBits(a.z) == Double.doubleToRawLongBits(b.z);
    }

    private static double d(long bits) {
        return Double.longBitsToDouble(bits);
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = StepCollisionMemo.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static List<EngineCheck> checks(SimulationProcessor processor) throws Exception {
        Field flagger = SimulationProcessor.class.getDeclaredField("flagger");
        flagger.setAccessible(true);
        Object runner = flagger.get(processor);
        Field checks = PredCheckRunner.class.getDeclaredField("checks");
        checks.setAccessible(true);
        return (List<EngineCheck>) checks.get(runner);
    }
}
