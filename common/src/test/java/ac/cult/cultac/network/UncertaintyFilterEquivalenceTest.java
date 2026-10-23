package ac.cult.cultac.network;

import static ac.cult.cultac.utils.math.VectorUtils.LARGE_MOVEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UncertaintyHandler;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.BubbleColumnData;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.StuckEdgeData;
import ac.cult.cultac.utils.data.StuckSpeedData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The extents pass without the handlers whose skip predicate holds, against the whole pipeline. */
class UncertaintyFilterEquivalenceTest {
    private static final int SCENARIOS = 66;

    private static final Vec3[] STARTS = {
        new Vec3(-0.0, -0.0, -0.0),
        new Vec3(0.0, -0.0, 0.0),
        new Vec3(-0.3, 0.0, -0.0),
        new Vec3(0.1, -0.0784, -0.2),
        new Vec3(1.0E-9, 0.42, -1.0E-9),
        new Vec3(-2.5, -3.9, 2.5),
    };

    private static final Vec3[] ENDS = {
        new Vec3(LARGE_MOVEMENT, 0, 0),
        new Vec3(0, -LARGE_MOVEMENT, 0),
        new Vec3(0, 0, -LARGE_MOVEMENT),
        new Vec3(-0.0, -0.0, -0.0),
        new Vec3(0.2, 0.1, -0.2),
    };

    /** Every scenario dumps the same with the filter as with every handler kept. */
    @Test
    void filteredExtentsDumpLikeTheWholePipeline() throws Exception {
        UncertaintyHandler[] modifiers = modifiers();
        UncertaintyHandler[] original = modifiers.clone();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            String filtered = PredictionDigestTest.dump(
                    seed, new TreeMap<>(), UncertaintyFilterEquivalenceTest::holdWorldResync);
            String whole;
            try {
                wrap(modifiers, i -> new Kept(original[i]));
                whole = PredictionDigestTest.dump(
                        seed, new TreeMap<>(), UncertaintyFilterEquivalenceTest::holdWorldResync);
            } finally {
                System.arraycopy(original, 0, modifiers, 0, original.length);
            }
            assertEquals(whole, filtered, "scenario " + seed);
        }
    }

    /**
     * Wherever a handler's predicate holds, it returns the trace it is given, and adversarial traces, by reference and
     * leaves the context's ground state alone.
     */
    @Test
    void aSkippedHandlerReturnsEveryTraceItself() throws Exception {
        UncertaintyHandler[] modifiers = modifiers();
        UncertaintyHandler[] original = modifiers.clone();
        Map<String, int[]> skips = new TreeMap<>();
        List<String> mismatches = new ArrayList<>();
        try {
            wrap(modifiers, i -> new Checking(original[i], i == 0, skips, mismatches));
            for (long seed = 0; seed < SCENARIOS; seed++) {
                try (var f = new JavaTickFixture()) {
                    PredictionScenarios.run(f, seed, result -> {});
                }
            }
        } finally {
            System.arraycopy(original, 0, modifiers, 0, original.length);
        }
        assertEquals(List.of(), mismatches.stream().limit(20).toList());
        for (UncertaintyHandler handler : original) {
            Method skip = handler.getClass()
                    .getMethod("skipsContext", CultPlayer.class, SimulationContext.class, PredictionResult.class);
            if (skip.getDeclaringClass() == UncertaintyHandler.class) continue;
            int[] counts = skips.get(handler.getClass().getSimpleName());
            assertTrue(counts != null && counts[0] > 0, handler.getClass().getSimpleName() + " never skipped");
        }
        for (String name : MUST_ACT) {
            int[] counts = skips.get(name);
            assertTrue(counts != null && counts[3] > 0, name + " never moved a trace under a variant");
        }
        System.out.println("skipped/called " + format(skips));
    }

    private static String format(Map<String, int[]> skips) {
        StringBuilder sb = new StringBuilder();
        skips.forEach((name, counts) -> sb.append(name)
                .append('=')
                .append(counts[0])
                .append('/')
                .append(counts[1])
                .append(" acting variants ")
                .append(counts[2])
                .append(" moving ")
                .append(counts[3])
                .append(' '));
        return sb.toString();
    }

    /**
     * SetbackTeleportUtil#resyncWorld resends at most once per five wall-clock seconds, and each resend takes a
     * transaction id; both dumps of a scenario run with it held shut so their ids cannot drift apart.
     */
    private static void holdWorldResync(JavaTickFixture f) {
        try {
            Field field = SetbackTeleportUtil.class.getDeclaredField("lastWorldResync");
            field.setAccessible(true);
            field.setLong(f.player.getSetbackTeleportUtil(), Long.MAX_VALUE / 2);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UncertaintyHandler[] modifiers() throws Exception {
        Field field = ValidMovements.class.getDeclaredField("MODIFIERS");
        field.setAccessible(true);
        return (UncertaintyHandler[]) field.get(null);
    }

    private static void wrap(UncertaintyHandler[] modifiers, IntFunction<UncertaintyHandler> wrapper) {
        for (int i = 0; i < modifiers.length; i++) modifiers[i] = wrapper.apply(i);
    }

    private static final int SLIME_STATE = 14379;
    private static final int BED_STATE = firstBedState();
    private static final int FIREWORK = 1_000_001;

    /** Handlers whose predicate the scenarios alone never turn false; a variant must make each one move a trace. */
    private static final Set<String> MUST_ACT = Set.of("BoatTransform", "Fireworks", "Honey", "StuckSpeed");

    private static final List<Variant> VARIANTS = List.of(
            Variant.of(Owner.WORLD, "inWater", () -> DesyncStatus.TRUE),
            Variant.of(Owner.WORLD, "inLava", () -> DesyncStatus.TRUE),
            Variant.of(Owner.WORLD, "inFlowingLiquid", () -> DesyncStatus.TRUE),
            Variant.of(Owner.WORLD, "mightBeInBlock", () -> true),
            Variant.of(Owner.WORLD, "climbingAtStart", () -> DesyncStatus.TRUE),
            Variant.of(Owner.WORLD, "fishingRodPulls", UncertaintyFilterEquivalenceTest::box),
            Variant.of(Owner.WORLD, "sneak", () -> new StuckEdgeData(true, false, false, true, false)),
            Variant.of(Owner.WORLD, "pistonPushes", () -> new PistonPushes(box(), Set.of())),
            Variant.of(Owner.LAST_WORLD, "climbing", () -> DesyncStatus.TRUE),
            Variant.of(Owner.LAST_WORLD, "onBlock", () -> SLIME_STATE),
            Variant.of(Owner.LAST_WORLD, "onBlock", () -> BED_STATE),
            Variant.of(Owner.LAST_WORLD, "honeySlide", () -> DesyncStatus.TRUE),
            Variant.of(Owner.LAST_WORLD, "bubbleColumn", () -> new BubbleColumnData(1, 1, 1, 1)),
            Variant.of(Owner.LAST_WORLD, "stuckSpeed", UncertaintyFilterEquivalenceTest::cobweb),
            Variant.of(Owner.LAST_WORLD, "pistonPushes", () -> new PistonPushes(box(), Set.of())),
            Variant.of(Owner.LAST_WORLD, "sneak", () -> new StuckEdgeData(false, true, true, false, true)),
            Variant.of(Owner.CONTEXT, "riptideLevel", () -> 3),
            Variant.of(Owner.CONTEXT, "isTestingPointThree", () -> true),
            Variant.of(Owner.CONTEXT, "gliding", () -> true),
            Variant.of(Owner.CONTEXT, "version", () -> ClientVersion.V_1_21_4),
            Variant.of(Owner.CONTEXT, "vehicle", UncertaintyFilterEquivalenceTest::boat),
            Variant.of(Owner.LAST_CONTEXT, "riptideLevel", () -> 3),
            Variant.of(Owner.LAST_CONTEXT, "gliding", () -> true),
            Variant.of(Owner.FIREWORKS, "activeFireworks", () -> IntArrayList.of(FIREWORK)),
            new Variant(
                    "honey before 1.21.11",
                    List.of(
                            new Edit(Owner.CONTEXT, "version", () -> ClientVersion.V_1_21_4),
                            new Edit(Owner.LAST_WORLD, "honeySlide", () -> DesyncStatus.TRUE))),
            new Variant(
                    "stuck speed before 1.21.11",
                    List.of(
                            new Edit(Owner.CONTEXT, "version", () -> ClientVersion.V_1_21_4),
                            new Edit(Owner.LAST_WORLD, "stuckSpeed", UncertaintyFilterEquivalenceTest::cobweb))),
            new Variant(
                    "boat ticked by the client",
                    List.of(
                            new Edit(Owner.CONTEXT, "vehicle", UncertaintyFilterEquivalenceTest::boat),
                            new Edit(Owner.PACKET_STATE, "vehicleMovementFromClientTick", () -> true))));

    private static final Edit LAST_ON_GROUND = new Edit(Owner.WORLD, "lastOnGround", () -> null);

    private static StuckSpeedData cobweb() {
        return new StuckSpeedData(new Vec3(0.25, 0.05, 0.25), new Vec3(0.25, 0.05, 0.25));
    }

    private static PacketEntity boat() {
        return new PacketEntity(EntityTypeIds.OAK_BOAT, 1_000_000);
    }

    @Test
    void variantStatesAreWhatTheyClaim() {
        assertTrue(BlockIds.is(SLIME_STATE, BlockIds.SLIME_BLOCK));
        assertTrue(ClientBlockProperties.isBed(BED_STATE));
    }

    private static int firstBedState() {
        try {
            for (int state = 0; state < 1 << 16; state++) {
                if (ClientBlockProperties.isBed(state)) return state;
            }
        } catch (RuntimeException pastTheLastState) {
            return -1;
        }
        return -1;
    }

    private static SimpleCollisionBox box() {
        return new SimpleCollisionBox(-0.2, -0.2, -0.2, 0.2, 0.2, 0.2);
    }

    private enum Owner {
        WORLD,
        LAST_WORLD,
        CONTEXT,
        LAST_CONTEXT,
        PACKET_STATE,
        FIREWORKS
    }

    /** One field of the player, the context, the last context or either's world. */
    private record Edit(Owner owner, String field, Supplier<Object> supplier) {
        Object owner(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
            SimulationContext last = lastResult == null ? null : lastResult.getSimulationContext();
            return switch (owner) {
                case WORLD -> context.getWorldData();
                case LAST_WORLD -> last == null ? null : last.getWorldData();
                case CONTEXT -> context;
                case LAST_CONTEXT -> last;
                case PACKET_STATE -> player.packetStateData;
                case FIREWORKS -> player.compensatedFireworks;
            };
        }

        Object set(Object target, Object to) {
            try {
                Field f = field(target.getClass());
                f.setAccessible(true);
                Object old = f.get(target);
                f.set(target, to);
                return old;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private Field field(Class<?> type) throws NoSuchFieldException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(field);
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(type + "." + field);
        }
    }

    /** Fields set together to values the scenarios never reach, restored afterwards. */
    private record Variant(String label, List<Edit> edits) {
        static Variant of(Owner owner, String field, Supplier<Object> supplier) {
            return new Variant(owner + "." + field, List.of(new Edit(owner, field, supplier)));
        }

        /** Applies every edit and returns the undo, or null if an owner is absent. */
        Runnable apply(CultPlayer player, SimulationContext context, PredictionResult lastResult) {
            Object[] owners = new Object[edits.size()];
            for (int i = 0; i < owners.length; i++) {
                owners[i] = edits.get(i).owner(player, context, lastResult);
                if (owners[i] == null) return null;
            }
            Object[] old = new Object[owners.length];
            for (int i = 0; i < owners.length; i++) {
                old[i] = edits.get(i).set(owners[i], edits.get(i).supplier().get());
            }
            return () -> {
                for (int i = owners.length - 1; i >= 0; i--) edits.get(i).set(owners[i], old[i]);
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Runs its handler on every trace. */
    private record Kept(UncertaintyHandler delegate) implements UncertaintyHandler {
        @Override
        public MovementTrace handleMovementTrace(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult,
                MovementTrace trace,
                Vec3 end) {
            return delegate.handleMovementTrace(player, valid, result, context, lastResult, trace, end);
        }

        @Override
        public PredVector handleUncertainty(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult,
                PredVector start,
                Vec3 end) {
            return delegate.handleUncertainty(player, valid, result, context, lastResult, start, end);
        }
    }

    /** Runs its handler on every trace and checks the handler's predicate against what it returned. */
    private record Checking(
            UncertaintyHandler delegate, boolean first, Map<String, int[]> skips, List<String> mismatches)
            implements UncertaintyHandler {
        @Override
        public MovementTrace handleMovementTrace(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult,
                MovementTrace trace,
                Vec3 end) {
            String name = delegate.getClass().getSimpleName();
            boolean skip = delegate.skipsContext(player, context, lastResult);
            DesyncStatus ground = context.getWorldData().getLastOnGround();
            MovementTrace out = delegate.handleMovementTrace(player, valid, result, context, lastResult, trace, end);
            int[] counts = skips.computeIfAbsent(name, k -> new int[4]);
            if (counts[1]++ % 5 == 0) {
                for (Variant variant : VARIANTS) {
                    Runnable undo = variant.apply(player, context, lastResult);
                    if (undo == null) continue;
                    try {
                        if (delegate.skipsContext(player, context, lastResult)) {
                            adversarial(player, valid, result, context, lastResult, name + " with " + variant);
                        } else {
                            counts[2]++;
                            if (MUST_ACT.contains(name) && moves(player, valid, result, context, lastResult))
                                counts[3]++;
                        }
                    } finally {
                        undo.run();
                    }
                }
            }
            if (!skip) return out;
            counts[0]++;
            if (out != trace) mismatches.add(name + " changed " + trace + " towards " + end);
            adversarial(player, valid, result, context, lastResult, name);
            if (context.getWorldData().getLastOnGround() != ground) mismatches.add(name + " wrote lastOnGround");
            return out;
        }

        private void adversarial(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult,
                String label) {
            DesyncStatus ground = context.getWorldData().getLastOnGround();
            for (Vec3 start : STARTS) {
                PredVector vector = new PredVector(start);
                // The first handler only ever sees the start trace.
                MovementTrace probe = first
                        ? MovementTrace.start(vector)
                        : new MovementTrace(vector, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
                for (Vec3 to : ENDS) {
                    MovementTrace moved =
                            delegate.handleMovementTrace(player, valid, result, context, lastResult, probe, to);
                    if (moved != probe) mismatches.add(label + " changed " + probe + " towards " + to);
                }
            }
            if (context.getWorldData().getLastOnGround() != ground) mismatches.add(label + " wrote lastOnGround");
        }

        /** Whether the handler returns another trace for some adversarial start and end. */
        private boolean moves(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult) {
            DesyncStatus ground = context.getWorldData().getLastOnGround();
            try {
                for (Vec3 start : STARTS) {
                    PredVector vector = new PredVector(start);
                    MovementTrace probe = first
                            ? MovementTrace.start(vector)
                            : new MovementTrace(vector, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
                    for (Vec3 to : ENDS) {
                        if (delegate.handleMovementTrace(player, valid, result, context, lastResult, probe, to)
                                != probe) {
                            return true;
                        }
                    }
                }
                return false;
            } finally {
                LAST_ON_GROUND.set(context.getWorldData(), ground);
            }
        }

        @Override
        public PredVector handleUncertainty(
                CultPlayer player,
                ValidMovements valid,
                PredictionResult result,
                SimulationContext context,
                PredictionResult lastResult,
                PredVector start,
                Vec3 end) {
            return delegate.handleUncertainty(player, valid, result, context, lastResult, start, end);
        }
    }
}
