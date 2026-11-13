package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.checks.impl.prediction.runner.PredictionScratch;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.PistonPushes;
import ac.cult.cultac.utils.data.ShulkerBoxShoveResult;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GeyserVelocity;
import ac.cult.cultac.utils.nmsutil.InsideBlockEffectScan;
import ac.cult.cultac.utils.nmsutil.ReusableVelocitySet;
import ac.cult.cultac.utils.nmsutil.WaterCurrent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The prediction paths that write a player's {@link PredictionScratch} against the same calls on a fresh scratch, as
 * the allocating code ran them, and the inside-block scan against its value-object form: identical raw bits on the
 * seeded scenarios' contexts, with special and generated moves.
 */
class PredictionScratchReuseEquivalenceTest {
    private static final double[] SPECIAL = {
        Double.NaN,
        Double.longBitsToDouble(0xFFF8_0000_0000_1234L),
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        Double.MIN_NORMAL / 3,
        -0.0,
        0.0,
        1.0E15,
        -Double.MAX_VALUE
    };

    private static final double[] FINITE_SPECIAL = {
        Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL / 3, -0.0, 0.0
    };

    private final int[] counts = new int[10];

    @Test
    void scratchReuseMatchesFreshBoxes() throws Exception {
        Field scratchField = SimulationProcessor.class.getDeclaredField("scratch");
        scratchField.setAccessible(true);
        Method moved = GeyserVelocity.class.getDeclaredMethod(
                "movedToActualPose", SimpleCollisionBox.class, SimulationContext.class, Vec3.class);
        moved.setAccessible(true);
        for (long seed = 0; seed < 66; seed++) {
            Random random = new Random(seed * 31 + 7);
            try (JavaTickFixture f = new JavaTickFixture()) {
                CultPlayer player = f.player;
                SimulationProcessor processor = player.checkManager.getSimulationProcessor();
                PredictionScratch shared = processor.scratch();
                PredictionScenarios.Terrain terrain = PredictionScenarios.terrain(seed);
                boolean[] flowing = {
                    terrain != PredictionScenarios.Terrain.WATER && terrain != PredictionScenarios.Terrain.LAVA
                };
                PredictionScenarios.run(f, seed, result -> {
                    if (!flowing[0]) {
                        // Levels below the source give the fluid a flow, so currents are not all zero.
                        BlockRegistry registry = DataTables.defaults().registry();
                        int source = registry.block(
                                        terrain == PredictionScenarios.Terrain.WATER
                                                ? "minecraft:water"
                                                : "minecraft:lava")
                                .defaultState();
                        for (int x = -3; x <= 3; x++)
                            for (int z = -3; z <= 3; z++)
                                for (int y = JavaTickFixture.FLOOR_Y; y <= JavaTickFixture.FLOOR_Y + 1; y++)
                                    player.compensatedWorld.updateBlock(
                                            x,
                                            y,
                                            z,
                                            registry.with(
                                                    source,
                                                    "level",
                                                    Integer.toString(1 + Math.floorMod(x * 3 + z, 7))));
                        flowing[0] = true;
                    }
                    if (result == null || result.getSimulationContext() == null) return;
                    try {
                        compare(player, processor, scratchField, shared, moved, result, random);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
        String summary = String.format(
                "scans %d (effects %d), geyser %d, currents %d (non-null %d), falls %d, extents %d, boxes %d, carried"
                        + " %d, matching throws %d",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7], counts[8],
                counts[9]);
        System.out.println(summary);
        assertTrue(counts[0] > 10_000, summary);
        assertTrue(counts[1] > 50, summary);
        assertTrue(counts[3] > 10_000, summary);
        assertTrue(counts[4] > 50, summary);
        assertTrue(counts[5] > 1_000, summary);
        assertTrue(counts[6] > 1_000, summary);
        assertTrue(counts[8] > 500, summary);
    }

    @Test
    void filterSetIteratesAsFreshSet() throws Exception {
        Method filtered = JavaMovementEngine.class.getDeclaredMethod("filteredVelocities", CultPlayer.class);
        filtered.setAccessible(true);
        Random random = new Random(11);
        try (JavaTickFixture f = new JavaTickFixture()) {
            PredictionScratch scratch =
                    f.player.checkManager.getSimulationProcessor().scratch();
            for (int leftover : new int[] {0, 5, 8, 9, 12, 13, 40}) {
                for (int size = 1; size <= 16; size++) {
                    ReusableVelocitySet leftovers = scratch.filteredVelocities();
                    for (int i = 0; i < leftover; i++) leftovers.add(generated(random));
                    leftovers.clear();
                    @SuppressWarnings("unchecked")
                    Set<Vec3> set = (Set<Vec3>) filtered.invoke(null, f.player);
                    assertTrue(set.isEmpty(), "filled after " + leftover);
                    Set<Vec3> fresh = new HashSet<>();
                    for (int i = 0; i < size; i++) {
                        Vec3 velocity = new Vec3(
                                random.nextGaussian() * 0.6,
                                random.nextGaussian() * 0.6,
                                random.nextGaussian() * 0.6);
                        set.add(velocity);
                        fresh.add(velocity);
                    }
                    assertEquals(List.copyOf(fresh), List.copyOf(set), "order of " + size + " after " + leftover);
                    set.clear();
                }
            }
        }
    }

    private void compare(
            CultPlayer player,
            SimulationProcessor processor,
            Field scratchField,
            PredictionScratch shared,
            Method moved,
            PredictionResult result,
            Random random)
            throws ReflectiveOperationException {
        SimulationContext context = result.getSimulationContext();
        List<Vec3> moves = new ArrayList<>();
        moves.add(context.getEnd().subtract(context.getStart()));
        if (result.getCollideAxisData() != null) moves.add(result.getAcceptedClosestToTarget());
        for (int i = 0; i < 4; i++) moves.add(generated(random, true));
        for (int i = 0; i < 8; i++) {
            Vec3 move = generated(random, false);
            Vec3 end = context.getStart().add(move);
            SimpleCollisionBox expected = context.getToActualPose().copy().offset(end.subtract(context.getEnd()));
            SimpleCollisionBox actual =
                    (SimpleCollisionBox) moved.invoke(null, poisoned(new SimpleCollisionBox()), context, end);
            assertEquals(describe(expected), describe(actual), "geyser box " + move);
            counts[7]++;
        }
        for (Vec3 move : moves) {
            Vec3 end = context.getStart().add(move);
            String where = context.getStart() + " + " + move;

            Outcome reference = run(processor, scratchField, null, () -> describe(oldScan(player, result, move)));
            same(
                    reference,
                    run(
                            processor,
                            scratchField,
                            shared,
                            () -> describe(JavaInsideBlockEffects.scan(player, result, move))),
                    "scan " + where);
            same(
                    reference,
                    run(
                            processor,
                            scratchField,
                            null,
                            () -> describe(JavaInsideBlockEffects.scan(player, result, move))),
                    "scan fresh " + where);
            counts[0]++;
            if (!"null".equals(reference.value())) counts[1]++;

            JavaInsideBlockEffects.State state = new JavaInsideBlockEffects.State(random.nextDouble() * 4, move, null);
            sameAsFresh(
                    run(
                            processor,
                            scratchField,
                            null,
                            () -> describe(GeyserVelocity.applyToState(player, result, end, state))),
                    run(
                            processor,
                            scratchField,
                            shared,
                            () -> describe(GeyserVelocity.applyToState(player, result, end, state))),
                    "geyser state " + where);
            Set<Vec3> velocities = new LinkedHashSet<>(List.of(move, Vec3.ZERO));
            sameAsFresh(
                    run(
                            processor,
                            scratchField,
                            null,
                            () -> describe(GeyserVelocity.applyToResult(player, result, velocities))),
                    run(
                            processor,
                            scratchField,
                            shared,
                            () -> describe(GeyserVelocity.applyToResult(player, result, velocities))),
                    "geyser result " + where);
            counts[2]++;

            for (Vec3 position : List.of(context.getStart(), context.getEnd(), end)) {
                Outcome water = run(
                        processor,
                        scratchField,
                        null,
                        () -> describe(WaterCurrent.calculateWaterCurrent(player, context, position, move)));
                sameAsFresh(
                        water,
                        run(
                                processor,
                                scratchField,
                                shared,
                                () -> describe(WaterCurrent.calculateWaterCurrent(player, context, position, move))),
                        "water " + where);
                Outcome lava = run(
                        processor,
                        scratchField,
                        null,
                        () -> describe(WaterCurrent.calculateLavaCurrent(player, context, position, move)));
                sameAsFresh(
                        lava,
                        run(
                                processor,
                                scratchField,
                                shared,
                                () -> describe(WaterCurrent.calculateLavaCurrent(player, context, position, move))),
                        "lava " + where);
                counts[3] += 2;
                if (!"null".equals(water.value())) counts[4]++;
                if (!"null".equals(lava.value())) counts[4]++;
            }

            if (result.getCollideAxisData() != null && result.getInitialStartingVel() != null) {
                sameAsFresh(
                        run(
                                processor,
                                scratchField,
                                null,
                                () -> describe(JavaFallDistance.afterCollision(player, result, move))),
                        run(
                                processor,
                                scratchField,
                                shared,
                                () -> describe(JavaFallDistance.afterCollision(player, result, move))),
                        "fall " + where);
                counts[5]++;
            }
        }
        if (context.getProfileCarry() instanceof JavaPredictionCarry selected
                && processor.getCurrentPredictionCarry() instanceof JavaPredictionCarry committed) {
            Set<Vec3> starts = new HashSet<>();
            for (var state : selected.states()) if (random.nextInt(4) != 0) starts.add(state.velocity());
            for (var state : committed.states()) starts.add(state.velocity());
            for (int i = random.nextInt(3); i > 0; i--) starts.add(generated(random));
            PredictionResult last = processor.getLastPrediction();
            Field filterSetField = PredictionScratch.class.getDeclaredField("filteredVelocities");
            filterSetField.setAccessible(true);
            for (int round = 0; round < 2; round++) {
                int leftover = random.nextInt(15);
                boolean canTickSkip = random.nextBoolean();
                Outcome reference = run(
                        processor,
                        scratchField,
                        null,
                        () -> describe(baseApplyModifiers(
                                new MovementModifiers(), player, starts, context, last, canTickSkip)));
                ReusableVelocitySet leftovers = shared.filteredVelocities();
                leftovers.clear();
                for (int i = 0; i < leftover; i++) leftovers.add(generated(random));
                leftovers.clear();
                same(
                        reference,
                        run(
                                processor,
                                scratchField,
                                shared,
                                () -> describe(JavaMovementEngine.INSTANCE.applyModifiers(
                                        new MovementModifiers(), player, starts, context, last, canTickSkip))),
                        "carried starts " + leftover);
                assertTrue(((Set<?>) filterSetField.get(shared)).isEmpty(), "scratch set left filled");
                same(
                        reference,
                        run(
                                processor,
                                scratchField,
                                null,
                                () -> describe(JavaMovementEngine.INSTANCE.applyModifiers(
                                        new MovementModifiers(), player, starts, context, last, canTickSkip))),
                        "carried starts fresh");
                counts[8]++;
            }
        }
        for (PredictionResult candidate : result.getAnyReality()) {
            if (candidate.getValidMovements() == null) continue;
            PredictionResult last = processor.getLastPrediction();
            sameAsFresh(
                    run(
                            processor,
                            scratchField,
                            null,
                            () -> describe(candidate.getValidMovements().computeAABB(last))),
                    run(
                            processor,
                            scratchField,
                            shared,
                            () -> describe(candidate.getValidMovements().computeAABB(last))),
                    "extents");
            counts[6]++;
        }
    }

    /** JavaInsideBlockEffects#scan as it ran on value objects. */
    private static Object[] oldScan(CultPlayer player, PredictionResult result, Vec3 movement) {
        var context = result.getSimulationContext();
        var actor = context.getVehicle() == null ? context.getEntities().getSelf() : context.getVehicle();
        if (actor.isDead || player.gamemode == GameMode.SPECTATOR) return null;
        Vec3 end = context.getStart().add(movement);
        SimpleCollisionBox to = context.getToActualPose().offset(end.subtract(context.getEnd()));
        SimpleCollisionBox from = to.copy().offset(movement.scale(-1));
        if (!InsideBlockEffectScan.mayHaveEffects(player, from, to, false)) return null;
        return new Object[] {actor, end, from, to};
    }

    /** JavaMovementEngine#applyModifiers as it filtered the carried starts into a new {@code HashSet}. */
    private static List<PredVector> baseApplyModifiers(
            MovementModifiers modifiers,
            CultPlayer player,
            Set<Vec3> startingVelocities,
            SimulationContext context,
            PredictionResult lastPrediction,
            boolean canTickSkip) {
        if (context.getProfileCarry() instanceof JavaPredictionCarry selected
                && !selected.states().isEmpty()
                && player.checkManager.getSimulationProcessor().getCurrentPredictionCarry()
                        instanceof JavaPredictionCarry committed
                && hasMatchingVelocity(committed, startingVelocities)) {
            Set<Vec3> filtered = new HashSet<>();
            for (var state : selected.states())
                if (startingVelocities.contains(state.velocity())) filtered.add(state.velocity());
            if (filtered.isEmpty()) return new ArrayList<>();
            startingVelocities = filtered;
        }
        PistonPushes pushes =
                context.getWorldData() == null ? null : context.getWorldData().getPistonPushes();
        ShulkerBoxShoveResult shove = pushes == null ? null : pushes.getShulkerBoxShoveResult();
        if (shove != null) {
            Set<Vec3> shoved = new LinkedHashSet<>(startingVelocities.size());
            for (Vec3 velocity : startingVelocities) shoved.add(shove.velocityAfter(velocity));
            startingVelocities = shoved;
        }
        return modifiers.applyModifers(player, startingVelocities, context, lastPrediction, canTickSkip);
    }

    private static boolean hasMatchingVelocity(JavaPredictionCarry carry, Set<Vec3> velocities) {
        for (var state : carry.states()) if (velocities.contains(state.velocity())) return true;
        return false;
    }

    /** A call's described result, or the exception it threw. */
    private record Outcome(String value, RuntimeException thrown) {}

    /**
     * {@code actual} against {@code reference}: the same described result, or, where the reference throws, an
     * exception of the same type with the same message. A throw the reference does not make fails the test.
     */
    private void same(Outcome reference, Outcome actual, String where) {
        if (reference.thrown() == null) {
            if (actual.thrown() != null) throw new AssertionError(where + ": the reference returned", actual.thrown());
            assertEquals(reference.value(), actual.value(), where);
            return;
        }
        if (actual.thrown() == null)
            throw new AssertionError(where + ": returned " + actual.value(), reference.thrown());
        assertEquals(reference.thrown().getClass(), actual.thrown().getClass(), where);
        assertEquals(reference.thrown().getMessage(), actual.thrown().getMessage(), where);
        counts[9]++;
    }

    /** {@link #same} against the new path on a fresh scratch, which none of these inputs makes throw. */
    private void sameAsFresh(Outcome fresh, Outcome actual, String where) {
        if (fresh.thrown() != null) throw new AssertionError(where + ": threw on a fresh scratch", fresh.thrown());
        same(fresh, actual, where);
    }

    /** {@code supplier} with the processor holding {@code scratch}, poisoned, or a fresh one when null. */
    private static Outcome run(
            SimulationProcessor processor, Field field, PredictionScratch scratch, Supplier<String> supplier)
            throws ReflectiveOperationException {
        PredictionScratch use = scratch == null ? new PredictionScratch() : poison(scratch);
        PredictionScratch previous = (PredictionScratch) field.get(processor);
        field.set(processor, use);
        try {
            return new Outcome(supplier.get(), null);
        } catch (RuntimeException e) {
            return new Outcome(null, e);
        } finally {
            field.set(processor, previous);
        }
    }

    private static PredictionScratch poison(PredictionScratch scratch) {
        Arrays.fill(scratch.extentCoordinates, Double.NaN);
        for (SimpleCollisionBox box : List.of(
                scratch.geyserBox, scratch.insideTo, scratch.insideFrom, scratch.fallWaterBox, scratch.fluidBox))
            poisoned(box);
        return scratch;
    }

    private static SimpleCollisionBox poisoned(SimpleCollisionBox box) {
        return box.set(Double.NaN, -1.0E300, Double.NaN, 7.0, Double.NaN, -3.0, true);
    }

    private static Vec3 generated(Random random) {
        return generated(random, true);
    }

    /** A move; world scans get finite values only, since a block loop over an infinite box never ends. */
    private static Vec3 generated(Random random, boolean finite) {
        return new Vec3(component(random, finite), component(random, finite), component(random, finite));
    }

    private static double component(Random random, boolean finite) {
        return switch (random.nextInt(6)) {
            case 0 ->
                finite
                        ? FINITE_SPECIAL[random.nextInt(FINITE_SPECIAL.length)]
                        : SPECIAL[random.nextInt(SPECIAL.length)];
            case 1 -> random.nextGaussian() * (finite ? 3 : 1.0E6);
            default -> random.nextGaussian() * 0.6;
        };
    }

    private static String describe(Object value) {
        StringBuilder sb = new StringBuilder();
        describe(sb, value);
        return sb.toString();
    }

    private static void describe(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof SimpleCollisionBox box) {
            sb.append("box[")
                    .append(bits(box.minX))
                    .append(',')
                    .append(bits(box.minY))
                    .append(',')
                    .append(bits(box.minZ))
                    .append(',')
                    .append(bits(box.maxX))
                    .append(',')
                    .append(bits(box.maxY))
                    .append(',')
                    .append(bits(box.maxZ))
                    .append(',')
                    .append(box.isFullBlock())
                    .append(']');
        } else if (value instanceof PredVector p) {
            sb.append(p)
                    .append('(')
                    .append(bits(p.x))
                    .append(',')
                    .append(bits(p.y))
                    .append(',')
                    .append(bits(p.z))
                    .append(')');
        } else if (value instanceof Vec3 v) {
            sb.append(v.getClass().getSimpleName())
                    .append('(')
                    .append(bits(v.x))
                    .append(',')
                    .append(bits(v.y))
                    .append(',')
                    .append(bits(v.z))
                    .append(')');
        } else if (value instanceof Double d) {
            sb.append(bits(d));
        } else if (value instanceof JavaInsideBlockEffects.Scan scan) {
            describe(sb, new Object[] {scan.actor(), scan.end(), scan.from(), scan.to()});
        } else if (value instanceof JavaInsideBlockEffects.State state) {
            describe(sb, new Object[] {state.fallDistance(), state.velocity(), state.stuckSpeed()});
        } else if (value instanceof JavaFallDistance.Moved m) {
            describe(sb, new Object[] {m.fallDistance(), m.movement(), m.landed(), m.touchingWater()});
        } else if (value instanceof Object[] array) {
            sb.append('{');
            for (Object o : array) {
                describe(sb, o);
                sb.append(';');
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            for (Object o : items) {
                describe(sb, o);
                sb.append(';');
            }
            sb.append(']');
        } else if (value instanceof Boolean || value instanceof Enum<?> || value instanceof String) {
            sb.append(value);
        } else {
            sb.append(value.getClass().getSimpleName()).append('@').append(System.identityHashCode(value));
        }
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }
}
