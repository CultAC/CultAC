package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionObserver;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CollisionModifier;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.JavaCollisionState;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Seeded prediction scenarios dumped tick by tick: offsets and vectors by raw bits, lineage, flags, alternatives,
 * collision probes, every probed context's world and the SuperDebug state. With {@code CULTAC_PREDICT_DIGEST} set to a
 * directory, each scenario's dump and a digest summary land there, to compare two source trees.
 */
class PredictionDigestTest {
    private static final int SCENARIOS = 66;

    /**
     * Set.of iterates in an order salted per JVM, and the next tick's velocity candidates come from one: pinning the
     * salt makes two JVMs, and so two source trees, try candidates in the same order.
     */
    private static void pinImmutableSetOrder() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe unsafe = (Unsafe) theUnsafe.get(null);
            Class<?> collections = Class.forName("java.util.ImmutableCollections");
            Field salt = collections.getDeclaredField("SALT32L");
            Field reverse = collections.getDeclaredField("REVERSE");
            unsafe.putLong(unsafe.staticFieldBase(salt), unsafe.staticFieldOffset(salt), 0x5A17L);
            unsafe.putBoolean(unsafe.staticFieldBase(reverse), unsafe.staticFieldOffset(reverse), false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void scenariosDumpDeterministically() throws Exception {
        String out = System.getenv("CULTAC_PREDICT_DIGEST");
        if (out != null) pinImmutableSetOrder();
        Map<String, Integer> stats = new TreeMap<>();
        StringBuilder summary = new StringBuilder();
        MessageDigest total = MessageDigest.getInstance("SHA-256");
        for (long seed = 0; seed < SCENARIOS; seed++) {
            String dump = dump(seed, stats);
            if (seed % 11 == 0) assertEquals(dump, dump(seed, new TreeMap<>()), "scenario " + seed);
            byte[] bytes = dump.getBytes(StandardCharsets.UTF_8);
            total.update(bytes);
            summary.append(HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .append(' ')
                    .append(seed)
                    .append(' ')
                    .append(PredictionScenarios.terrain(seed))
                    .append('\n');
            if (out != null) {
                Path dir = Path.of(out);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(seed + ".txt"), dump);
            }
        }
        summary.append(HexFormat.of().formatHex(total.digest())).append(" total\n");
        summary.append("ticks ").append(stats).append('\n');
        if (out != null) Files.writeString(Path.of(out).resolve("summary.txt"), summary);
        System.out.print(summary);
    }

    static String dump(long seed, Map<String, Integer> stats) throws Exception {
        return dump(seed, stats, f -> {});
    }

    /** {@code prepare} runs on the fixture before the scenario. */
    static String dump(long seed, Map<String, Integer> stats, Consumer<JavaTickFixture> prepare) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var f = new JavaTickFixture()) {
            prepare.accept(f);
            SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
            RecordingProbe probe = new RecordingProbe(sb);
            Field field = SimulationProcessor.class.getDeclaredField("collisionProbe");
            field.setAccessible(true);
            field.set(processor, probe);
            Field carry = SimulationProcessor.class.getDeclaredField("profileCarry");
            carry.setAccessible(true);
            f.player.checkManager.getListener(SuperDebug.class).watch(new DumpObserver(sb));
            int[] tick = {0};
            PredictionScenarios.run(f, seed, result -> {
                stats.merge("contexts " + probe.contexts.size(), 1, Integer::sum);
                stats.merge(
                        result == null ? "none" : JavaTickFixture.exact(result) ? "exact" : "inexact", 1, Integer::sum);
                try {
                    if (carry.get(processor) instanceof JavaPredictionCarry next) {
                        stats.merge("carry " + next.states().size(), 1, Integer::sum);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                probe.contexts.clear();
                sb.append("tick ").append(tick[0]++).append(' ');
                appendResult(sb, result);
            });
        }
        return sanitize(sb.toString());
    }

    private static void appendResult(StringBuilder sb, PredictionResult result) {
        if (result == null) {
            sb.append("none\n");
            return;
        }
        sb.append("offset ")
                .append(bits(result.getOffset()))
                .append(" exempt ")
                .append(result.isExempt())
                .append(" teleport ")
                .append(result.isTeleport())
                .append(" severity ")
                .append(bits(result.getFlagSeverity()))
                .append(" flags ")
                .append(result.getFlags())
                .append("\n target ")
                .append(vec(result.getTarget()))
                .append("\n start ");
        lineage(sb, result.getInitialStartingVel());
        appendMovements(sb, result);
        sb.append("\n context ").append(result.getSimulationContext()).append('\n');
        appendRealities(sb, "reality", result.getRealities());
        appendRealities(sb, "any", result.getAnyReality());
    }

    private static void appendRealities(StringBuilder sb, String label, List<PredictionResult> realities) {
        if (realities == null) return;
        for (PredictionResult reality : realities) {
            sb.append(' ').append(label).append(' ');
            if (reality.getValidMovements() != null) sb.append(bits(reality.getOffset()));
            sb.append(" start ");
            lineage(sb, reality.getInitialStartingVel());
            appendMovements(sb, reality);
            sb.append('\n');
        }
    }

    private static void appendMovements(StringBuilder sb, PredictionResult result) {
        sb.append("\n collide ").append(axes(result.getCollideAxisData()));
        ValidMovements valid = result.getValidMovements();
        if (valid == null) return;
        sb.append("\n closest ")
                .append(vec(valid.getClosestToTarget()))
                .append(" extents ")
                .append(box(valid.getCollisionIgnoredMaxStartingVelExtents()))
                .append(" step ")
                .append(valid.isCanStep())
                .append(" vertical ")
                .append(valid.isControlsVerticalMovement())
                .append(" skip ")
                .append(valid.isDidTickSkipGravity())
                .append("\n trace ")
                .append(valid.getClosestTrace());
    }

    static void lineage(StringBuilder sb, PredVector vector) {
        try {
            Field last = PredVector.class.getDeclaredField("lastVector");
            Field reason = PredVector.class.getDeclaredField("reason");
            last.setAccessible(true);
            reason.setAccessible(true);
            for (PredVector v = vector; v != null; v = (PredVector) last.get(v)) {
                sb.append(vec(v)).append(' ').append(reason.get(v)).append(" <- ");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static String axes(CollideAxisData data) {
        if (data == null) return "null";
        return axis(data.getX())
                + ' '
                + axis(data.getYPos())
                + ' '
                + axis(data.getYNeg())
                + ' '
                + axis(data.getZ())
                + ' '
                + data.getUnknown();
    }

    private static String axis(CollideAxisData.CollideResult result) {
        if (result == null) return "-";
        return result.isLikelyCollide() + ":" + bits(result.getResult()) + ":" + result.getSide();
    }

    static String vec(Vec3 v) {
        return v == null ? "null" : bits(v.x) + ',' + bits(v.y) + ',' + bits(v.z);
    }

    private static String box(SimpleCollisionBox b) {
        return b == null
                ? "null"
                : bits(b.minX)
                        + ','
                        + bits(b.minY)
                        + ','
                        + bits(b.minZ)
                        + ','
                        + bits(b.maxX)
                        + ','
                        + bits(b.maxY)
                        + ','
                        + bits(b.maxZ);
    }

    static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    /** Identity hashes and hidden-class addresses differ between JVMs; everything else is compared. */
    private static String sanitize(String dump) {
        return dump.replaceAll("@[0-9a-f]{4,}", "@").replaceAll("0x[0-9a-f]+", "0x");
    }

    /** Dumps each probe it runs with the probed context's world; counts the contexts probed in a tick. */
    static final class RecordingProbe extends CollisionModifier {
        private final StringBuilder sb;
        final Map<SimulationContext, Integer> contexts = new IdentityHashMap<>();

        RecordingProbe(StringBuilder sb) {
            this.sb = sb;
        }

        @Override
        public CollideAxisData probeCollisions(
                CultPlayer player,
                SimulationContext context,
                double minY,
                Vec3 target,
                Vec3 playerPos,
                SimpleCollisionBox attemptedMovementExtents,
                ProbeDimensions dimensions,
                JavaCollisionState actor,
                boolean followCandidateVerticalMovement,
                double horizontalInputReach) {
            CollideAxisData result = super.probeCollisions(
                    player,
                    context,
                    minY,
                    target,
                    playerPos,
                    attemptedMovementExtents,
                    dimensions,
                    actor,
                    followCandidateVerticalMovement,
                    horizontalInputReach);
            Integer index = contexts.get(context);
            if (index == null) {
                index = contexts.size();
                contexts.put(context, index);
                sb.append(" world ")
                        .append(index)
                        .append(' ')
                        .append(context.getWorldData())
                        .append(" boxes ")
                        .append(context.getEntityCollisionBoxesForMovementTick())
                        .append(" hard ")
                        .append(context.getHardCollidingEntityCollisionsForMovementTick())
                        .append('\n');
            }
            sb.append(" probe ")
                    .append(index)
                    .append(' ')
                    .append(bits(minY))
                    .append(' ')
                    .append(vec(target))
                    .append(' ')
                    .append(vec(playerPos))
                    .append(' ')
                    .append(followCandidateVerticalMovement)
                    .append(" -> ")
                    .append(axes(result))
                    .append('\n');
            return result;
        }
    }

    private static final class DumpObserver implements PredictionObserver {
        private final StringBuilder sb;
        private long seq;

        DumpObserver(StringBuilder sb) {
            this.sb = sb;
        }

        @Override
        public long pendingSeq() {
            return seq++;
        }

        @Override
        public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos) {
            sb.append(" debug ")
                    .append(seq)
                    .append(' ')
                    .append(bits(offset))
                    .append(' ')
                    .append(trace)
                    .append('\n')
                    .append(state)
                    .append('\n');
        }

        @Override
        public void failed(RuntimeException failure) {
            throw failure;
        }
    }
}
