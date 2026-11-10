package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.checks.impl.prediction.PredictionObserver;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.pipeline.MovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code CULTAC_CPU_BENCH=predict}: allocation and CPU of the prediction stages on the seeded scenarios' candidates, per
 * call. Run on two source trees in turn and compare the medians.
 */
class PredictionAllocationBenchTest {
    private static final int SEEDS = 66;
    private static final int ROUNDS = 30;
    private static final int WARMUP = 15;

    @Test
    void benchPredictionStages() throws Throwable {
        assumeTrue("predict".equals(System.getenv("CULTAC_CPU_BENCH")));
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<PredictionResult> candidates = new ArrayList<>();
        List<PredictionResult> lasts = new ArrayList<>();
        List<JavaTickFixture> fixtures = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        List<List<PredictionResult>> committed = new ArrayList<>();
        try {
            for (long seed = 0; seed < SEEDS; seed++) {
                JavaTickFixture f = new JavaTickFixture();
                fixtures.add(f);
                List<PredictionResult> results = new ArrayList<>();
                committed.add(results);
                PredictionResult[] last = {null};
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    for (PredictionResult candidate : result.getAnyReality()) {
                        if (candidate.getValidMovements() == null) continue;
                        candidates.add(candidate);
                        lasts.add(last[0]);
                        owners.add(fixtures.size() - 1);
                    }
                    results.add(result);
                    last[0] = result;
                });
            }
            Field pointThree = SimulationContext.class.getDeclaredField("isTestingPointThree");
            pointThree.setAccessible(true);
            int n = candidates.size();
            double[] sink = new double[1];
            Vec3[] inputs = new Vec3[64];
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(SimulationProcessor.class, MethodHandles.lookup());
            MethodHandle probe = lookup.findVirtual(
                    SimulationProcessor.class,
                    "probeCollisions",
                    MethodType.methodType(
                            CollideAxisData.class,
                            CultPlayer.class,
                            MovementEngine.class,
                            PredictionResult.class,
                            SimpleCollisionBox.class,
                            Vec3.class,
                            boolean.class));
            MethodHandle reuse = reuseNextResult();
            Field collisionProbe = SimulationProcessor.class.getDeclaredField("collisionProbe");
            collisionProbe.setAccessible(true);
            List<SimulationProcessor> processors = new ArrayList<>();
            List<CultPlayer> players = new ArrayList<>();
            List<CollisionModifier> modifiers = new ArrayList<>();
            List<HorizontalAnalyzer> analyzers = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CultPlayer player = fixtures.get(owners.get(i)).player;
                SimulationProcessor processor = player.checkManager.getSimulationProcessor();
                players.add(player);
                processors.add(processor);
                modifiers.add((CollisionModifier) collisionProbe.get(processor));
            }
            for (int i = 0; i < fixtures.size(); i++) analyzers.add(new HorizontalAnalyzer());
            Stage stepProbe = new Stage("step-eligibility probe", n * 4);
            Stage plainProbe = new Stage("probe", n * 4);
            Stage horizontal = new Stage("HorizontalAnalyzer.handleResult", n * 4);

            Stage aabb = new Stage("computeAABB", n * 10);
            Stage aabbPointThree = new Stage("computeAABB 0.03", n * 10);
            Stage trace = new Stage("transformTraceTowards", n * 40);
            Stage input = new Stage("getBestTheoreticalPlayerInput", n * 400);
            Stage capture = new Stage("SuperDebug capture", 0);
            for (List<PredictionResult> results : committed) capture.calls += 4 * results.size();
            for (int round = -WARMUP; round < ROUNDS; round++) {
                aabb.start(threads);
                for (int i = 0; i < n * 10; i++) {
                    ValidMovements valid = candidates.get(i % n).getValidMovements();
                    sink[0] += valid.computeAABB(lasts.get(i % n)).maxX;
                }
                aabb.stop(threads, round);

                aabbPointThree.start(threads);
                for (int i = 0; i < n * 10; i++) {
                    PredictionResult candidate = candidates.get(i % n);
                    SimulationContext context = candidate.getSimulationContext();
                    boolean testing = context.isTestingPointThree();
                    pointThree.setBoolean(context, true);
                    sink[0] += candidate.getValidMovements().computeAABB(lasts.get(i % n)).maxY;
                    pointThree.setBoolean(context, testing);
                }
                aabbPointThree.stop(threads, round);

                trace.start(threads);
                for (int i = 0; i < n * 40; i++) {
                    PredictionResult candidate = candidates.get(i % n);
                    sink[0] += candidate
                            .getValidMovements()
                            .transformTraceTowards(candidate.getTarget(), lasts.get(i % n))
                            .position()
                            .z;
                }
                trace.stop(threads, round);

                input.start(threads);
                for (int i = 0; i < n * 400; i++) {
                    PredictionResult candidate = candidates.get(i % n);
                    Vec3 target = candidate.getTarget();
                    inputs[i & 63] = HorizontalAnalyzer.getBestTheoreticalPlayerInput(
                            target.x - candidate.getInitialStartingVel().x,
                            target.z - candidate.getInitialStartingVel().z,
                            candidate.getSimulationContext().getXRot(),
                            ClientVersion.V_26_3);
                }
                input.stop(threads, round);

                capture.start(threads);
                for (int s = 0; s < fixtures.size() * 4; s++) {
                    SuperDebug debug = fixtures.get(s % fixtures.size())
                            .player
                            .checkManager
                            .getListener(SuperDebug.class);
                    debug.watch(DISCARD);
                    for (PredictionResult result : committed.get(s % fixtures.size())) {
                        debug.onPredictionComplete(new PredictionComplete(result));
                    }
                    debug.unwatch(DISCARD);
                }
                capture.stop(threads, round);

                plainProbe.start(threads);
                for (int i = 0; i < n * 4; i++) sink[0] += probe(probe, processors, players, candidates, i % n);
                plainProbe.stop(threads, round);

                stepProbe.start(threads);
                for (int i = 0; i < n * 4; i++) {
                    if (reuse != null) reuse.invokeExact(modifiers.get(i % n), true);
                    sink[0] += probe(probe, processors, players, candidates, i % n);
                }
                stepProbe.stop(threads, round);

                horizontal.start(threads);
                for (int i = 0; i < n * 4; i++) {
                    int c = i % n;
                    analyzers.get(owners.get(c)).handleResult(players.get(c), candidates.get(c), lasts.get(c));
                }
                horizontal.stop(threads, round);
            }
            System.out.printf(
                    "predict bench: %d candidates, %d captures, sink %s %s%n", n, capture.calls, sink[0], inputs[0]);
            for (Stage stage :
                    List.of(aabb, aabbPointThree, trace, input, capture, plainProbe, stepProbe, horizontal)) {
                stage.print();
            }
        } finally {
            for (JavaTickFixture f : fixtures) f.close();
        }
    }

    /** The candidate's probe as the runner makes it, 1 when it could collide horizontally. */
    private static int probe(
            MethodHandle probe,
            List<SimulationProcessor> processors,
            List<CultPlayer> players,
            List<PredictionResult> candidates,
            int c)
            throws Throwable {
        PredictionResult candidate = candidates.get(c);
        SimpleCollisionBox extents = candidate.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
        CollideAxisData data = (CollideAxisData) probe.invokeExact(
                processors.get(c),
                players.get(c),
                (MovementEngine) JavaMovementEngine.INSTANCE,
                candidate,
                extents,
                (Vec3) candidate.getSimulationContext().getTarget(),
                true);
        return data.couldCollideHorizontally() ? 1 : 0;
    }

    /** The modifier's reuseNextResult(boolean), or null on a tree without it. */
    private static MethodHandle reuseNextResult() throws IllegalAccessException {
        try {
            return MethodHandles.lookup()
                    .findVirtual(
                            CollisionModifier.class,
                            "reuseNextResult",
                            MethodType.methodType(void.class, boolean.class));
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static final PredictionObserver DISCARD = new PredictionObserver() {
        @Override
        public long pendingSeq() {
            return 0;
        }

        @Override
        public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos) {}

        @Override
        public void failed(RuntimeException failure) {
            throw failure;
        }
    };

    private static final class Stage {
        final String name;
        int calls;
        final long[] bytes = new long[ROUNDS];
        final long[] cpu = new long[ROUNDS];
        long bytes0;
        long cpu0;

        Stage(String name, int calls) {
            this.name = name;
            this.calls = calls;
        }

        void start(ThreadMXBean threads) {
            bytes0 = threads.getCurrentThreadAllocatedBytes();
            cpu0 = threads.getCurrentThreadCpuTime();
        }

        void stop(ThreadMXBean threads, int round) {
            long allocated = threads.getCurrentThreadAllocatedBytes() - bytes0;
            long used = threads.getCurrentThreadCpuTime() - cpu0;
            if (round < 0) return;
            bytes[round] = allocated;
            cpu[round] = used;
        }

        void print() {
            long[] b = bytes.clone();
            long[] c = cpu.clone();
            Arrays.sort(b);
            Arrays.sort(c);
            System.out.printf(
                    "predict %s: %.1f B/call (p10 %.1f, p90 %.1f), cpu %.1f ns/call (p10 %.1f, p90 %.1f)%n",
                    name,
                    b[ROUNDS / 2] / (double) calls,
                    b[ROUNDS / 10] / (double) calls,
                    b[ROUNDS * 9 / 10] / (double) calls,
                    c[ROUNDS / 2] / (double) calls,
                    c[ROUNDS / 10] / (double) calls,
                    c[ROUNDS * 9 / 10] / (double) calls);
        }
    }
}
