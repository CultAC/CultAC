package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.StepCollisionMemo;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.EntityWorldCollisions;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The step proof's shared pose box and the block-change reach test's processor-owned boxes against the base code that
 * built a fresh box per use, on the seeded scenarios' predictions and on randomized and edge block positions.
 */
class PredictionScratchEquivalenceTest {
    private static final int SCENARIOS = 66;
    private static final int RANDOM_POSITIONS = 120;
    private static final double[] DIRT = {
        Double.NaN, -0.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0E300, 4.9E-324
    };

    @Test
    void stepProofMatchesTheFreshPoseCode() throws Exception {
        Class<?> type = Class.forName("ac.cult.cultac.checks.impl.prediction.checks.VanillaStepProof");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object proof = constructor.newInstance();
        Method prove = type.getDeclaredMethod("proveSourceStep", CultPlayer.class, PredictionResult.class);
        prove.setAccessible(true);
        Field open = StepCollisionMemo.class.getDeclaredField("open");
        open.setAccessible(true);
        Field keys = StepCollisionMemo.class.getDeclaredField("keys");
        keys.setAccessible(true);
        Field size = StepCollisionMemo.class.getDeclaredField("size");
        size.setAccessible(true);
        SplittableRandom random = new SplittableRandom(7);
        SimpleCollisionBox dirty = new SimpleCollisionBox();
        int[] proofs = {0, 0};
        int[] poses = {0};
        int[] keyed = {0};
        List<String> mismatches = new ArrayList<>();
        for (long seed = 0; seed < SCENARIOS; seed++) {
            long scenario = seed;
            try (var f = new JavaTickFixture()) {
                StepCollisionMemo memo =
                        f.player.checkManager.getSimulationProcessor().getStepCollisionMemo();
                PredictionScenarios.run(f, seed, result -> {
                    if (result == null) return;
                    for (PredictionResult candidate : result.getAnyReality()) {
                        SimulationContext context = candidate.getSimulationContext();
                        if (context == null || candidate.getValidMovements() == null) continue;
                        dirty(dirty, random);
                        assertBits(context.getFromActualPose(), context.getFromActualPose(dirty), dirty, "from pose");
                        dirty(dirty, random);
                        assertBits(context.getToMaximumExtent(), context.getToMaximumExtent(dirty), dirty, "extent");
                        poses[0]++;
                        if (!candidate.getValidMovements().isCanStep()) continue;
                        try {
                            boolean wasOpen = open.getBoolean(memo);
                            try {
                                // The memo keys hold the raw bits of every box and move a path collided with.
                                memo.open(true);
                                boolean expected = BaseStepProof.proveSourceStep(f.player, candidate);
                                long[] expectedKeys = Arrays.copyOf((long[]) keys.get(memo), size.getInt(memo) * 10);
                                memo.open(true);
                                boolean actual = (boolean) prove.invoke(proof, f.player, candidate);
                                long[] actualKeys = Arrays.copyOf((long[]) keys.get(memo), size.getInt(memo) * 10);
                                if (expected != actual) mismatches.add(scenario + ": " + expected + " != " + actual);
                                if (!Arrays.equals(expectedKeys, actualKeys))
                                    mismatches.add(scenario + ": keys " + Arrays.toString(expectedKeys) + " != "
                                            + Arrays.toString(actualKeys));
                                proofs[expected ? 0 : 1]++;
                                keyed[0] += expectedKeys.length / 10;
                            } finally {
                                if (wasOpen) memo.open(true);
                                else memo.close();
                            }
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                });
            }
        }
        assertEquals(List.of(), mismatches);
        assertTrue(proofs[0] > 20 && proofs[1] > 20, "proven " + proofs[0] + " unproven " + proofs[1]);
        assertTrue(poses[0] > SCENARIOS * 10, poses[0] + " poses");
        assertTrue(keyed[0] > SCENARIOS, keyed[0] + " memo keys");
    }

    @Test
    void blockChangeReachMatchesTheFreshBoxCode() throws Exception {
        SplittableRandom random = new SplittableRandom(11);
        SimpleCollisionBox dirty = new SimpleCollisionBox();
        int[] edges = {0, Integer.MAX_VALUE, Integer.MIN_VALUE, -1, 1, 29_999_999, -30_000_000};
        for (int x : edges)
            for (int y : edges)
                for (int z : edges) {
                    BlockPos pos = new BlockPos(x, y, z);
                    dirty(dirty, random);
                    SimpleCollisionBox filled = dirty.set(
                            pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                    assertBits(new SimpleCollisionBox(pos), filled, dirty, "block " + pos);
                }
        int hits = 0, misses = 0, vehicles = 0;
        for (long seed = 0; seed < SCENARIOS; seed++) {
            try (var f = new JavaTickFixture()) {
                PredictionScenarios.run(f, seed, result -> {});
                SimulationProcessor processor = f.player.checkManager.getSimulationProcessor();
                PredictionResult last = processor.getLastPrediction();
                if (last == null || last.getSimulationContext() == null) continue;
                SimulationContext context = last.getSimulationContext();
                WorldData world = context.getWorldData();
                boolean inVehicle = f.player.compensatedEntities.getSelf().inVehicle();
                SimpleCollisionBox reach = context.getToMaximumExtent().expand(1.0);
                List<BlockPos> positions = new ArrayList<>();
                int[] xs = around(reach.minX, reach.maxX), ys = around(reach.minY, reach.maxY);
                int[] zs = around(reach.minZ, reach.maxZ);
                for (int x : xs) for (int y : ys) for (int z : zs) positions.add(new BlockPos(x, y, z));
                for (int i = 0; i < RANDOM_POSITIONS; i++) {
                    positions.add(new BlockPos(
                            random.nextInt(xs[0] - 3, xs[xs.length - 1] + 4),
                            random.nextInt(ys[0] - 3, ys[ys.length - 1] + 4),
                            random.nextInt(zs[0] - 3, zs[zs.length - 1] + 4)));
                }
                for (BlockPos pos : positions) {
                    boolean expected = !inVehicle && baseInReach(context, pos);
                    DesyncStatus before = world.getInWater();
                    world.setInWater(DesyncStatus.FALSE);
                    processor.handleBlockChange(pos, BlockIds.WATER.defaultState());
                    boolean actual = world.getInWater() == DesyncStatus.UNKNOWN;
                    world.setInWater(before);
                    assertEquals(expected, actual, "seed " + seed + " block " + pos + " reach " + reach);
                    if (inVehicle) vehicles++;
                    else if (expected) hits++;
                    else misses++;
                }
            }
        }
        assertTrue(
                hits > SCENARIOS && misses > SCENARIOS,
                "hits " + hits + " misses " + misses + " in a vehicle " + vehicles);
    }

    private static boolean baseInReach(SimulationContext context, BlockPos pos) {
        SimpleCollisionBox blockCollisionBox = new SimpleCollisionBox(pos);
        SimpleCollisionBox playerMaxExtent = context.getToMaximumExtent();
        playerMaxExtent.expand(1.0);
        return blockCollisionBox.isIntersected(playerMaxExtent);
    }

    private static int[] around(double min, double max) {
        int lo = (int) Math.floor(min), hi = (int) Math.ceil(max);
        return new int[] {lo - 2, lo - 1, lo, lo + 1, hi - 2, hi - 1, hi, hi + 1};
    }

    private static void dirty(SimpleCollisionBox box, SplittableRandom random) {
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            v[i] = random.nextInt(3) == 0 ? DIRT[random.nextInt(DIRT.length)] : random.nextDouble(-64, 64);
        }
        box.set(v[0], v[1], v[2], v[3], v[4], v[5], random.nextBoolean());
    }

    private static void assertBits(SimpleCollisionBox expected, SimpleCollisionBox actual, Object out, String what) {
        assertSame(out, actual, what + " returns its target");
        assertEquals(Arrays.toString(bits(expected)), Arrays.toString(bits(actual)), what);
        assertEquals(expected.isFullBlock(), actual.isFullBlock(), what + " full block");
    }

    private static long[] bits(SimpleCollisionBox box) {
        return new long[] {
            Double.doubleToRawLongBits(box.minX), Double.doubleToRawLongBits(box.minY),
            Double.doubleToRawLongBits(box.minZ), Double.doubleToRawLongBits(box.maxX),
            Double.doubleToRawLongBits(box.maxY), Double.doubleToRawLongBits(box.maxZ)
        };
    }

    /** VanillaStepProof.proveSourceStep at the base commit; the memo's package-private queries go through reflection. */
    private static final class BaseStepProof {
        private static final double STEP_Y_MATCH_EPSILON = 1.0E-5D;
        private static final double AXIS_SAMPLE_EPSILON = 1.0E-9D;
        private static final Method STEPPED;
        private static final Method BELOW;

        static {
            try {
                STEPPED = StepCollisionMemo.class.getDeclaredMethod(
                        "steppedCollision",
                        CultPlayer.class,
                        SimpleCollisionBox.class,
                        double.class,
                        double.class,
                        double.class,
                        List.class,
                        boolean.class);
                STEPPED.setAccessible(true);
                BELOW = StepCollisionMemo.class.getDeclaredMethod(
                        "collidesDirectlyBelow", CultPlayer.class, SimpleCollisionBox.class);
                BELOW.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static StepCollisionMemo memo(CultPlayer player) {
            return player.checkManager.getSimulationProcessor().getStepCollisionMemo();
        }

        private static Object invoke(Method method, Object target, Object... args) {
            try {
                return method.invoke(target, args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }

        static boolean proveSourceStep(CultPlayer player, PredictionResult result) {
            if (!(result.getCollideAxisData() instanceof EntityWorldCollisions worlds)) {
                return proveSourceStep(player, result, List.of(), !hasUnknownCollisionAtDestination(result));
            }
            for (List<SimpleCollisionBox> colliders : worlds.colliders()) {
                if (proveSourceStep(player, result, colliders, true)) return true;
            }
            return false;
        }

        private static boolean hasUnknownCollisionAtDestination(PredictionResult result) {
            for (SimpleCollisionBox box : result.getCollideAxisData().getUnknown()) {
                if (box.isIntersected(result.getSimulationContext().getToMaximumExtent())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasCollisionDirectlyBelowDestination(CultPlayer player, PredictionResult result) {
            return (boolean) invoke(
                    BELOW, memo(player), player, result.getSimulationContext().getToCubeCollision());
        }

        private static boolean anyDirectlyBelow(List<SimpleCollisionBox> colliders, SimpleCollisionBox box) {
            double probe = -SimpleCollisionBox.COLLISION_EPSILON;
            for (int i = 0; i < colliders.size(); i++) {
                if (colliders.get(i).collideY(box, probe, SimpleCollisionBox.AxisEpsilon.JAVA) != probe) return true;
            }
            return false;
        }

        private static boolean proveSourceStep(
                CultPlayer player, PredictionResult result, List<SimpleCollisionBox> colliders, boolean selectable) {
            boolean requireSelectedStep = selectable
                    && !hasCollisionDirectlyBelowDestination(player, result)
                    && !anyDirectlyBelow(
                            colliders, result.getSimulationContext().getToCubeCollision());
            Vec3 target = result.getTarget();
            double desiredY = result.getInitialStartingVel().y;
            if (collidesToAcceptedY(player, result, target.x, desiredY, target.z, requireSelectedStep, colliders)) {
                return true;
            }

            SimpleCollisionBox attempted = result.getValidMovements().getCollisionIgnoredMaxStartingVelExtents();
            if (attempted == null) {
                return false;
            }

            double[] candidateX = axisSamples(target.x, attempted.minX, attempted.maxX);
            double[] candidateY =
                    axisSamples(target.y, Math.min(desiredY, attempted.minY), Math.max(desiredY, attempted.maxY));
            double[] candidateZ = axisSamples(target.z, attempted.minZ, attempted.maxZ);
            for (double desiredX : candidateX) {
                for (double stepDesiredY : candidateY) {
                    for (double desiredZ : candidateZ) {
                        if (collidesToAcceptedY(
                                player, result, desiredX, stepDesiredY, desiredZ, requireSelectedStep, colliders)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private static boolean collidesToAcceptedY(
                CultPlayer player,
                PredictionResult result,
                double desiredX,
                double desiredY,
                double desiredZ,
                boolean requireSelectedStep,
                List<SimpleCollisionBox> colliders) {
            Vec3 stuck = result.getSimulationContext().getLastStuckSpeed();
            desiredX *= stuck.x;
            desiredY *= stuck.y;
            desiredZ *= stuck.z;
            List<Collisions.Axis> order = requireSelectedStep
                    ? (Math.abs(desiredX) < Math.abs(desiredZ)
                            ? List.of(Collisions.Axis.Y, Collisions.Axis.Z, Collisions.Axis.X)
                            : List.of(Collisions.Axis.Y, Collisions.Axis.X, Collisions.Axis.Z))
                    : null;
            Vec3 collidedMovement;
            Vec3 collisionWithoutStep = null;
            if (order != null && !player.isBedrockMovement()) {
                boolean onGroundForStep =
                        result.getSimulationContext().getLastOnGround().determineOptimistically();
                Collisions.SteppedCollision collision = colliders.isEmpty()
                        ? (Collisions.SteppedCollision) invoke(
                                STEPPED,
                                memo(player),
                                player,
                                result.getSimulationContext().getFromActualPose(),
                                desiredX,
                                desiredY,
                                desiredZ,
                                order,
                                onGroundForStep)
                        : Collisions.collideWithStepComparison(
                                player,
                                result.getSimulationContext().getFromActualPose(),
                                desiredX,
                                desiredY,
                                desiredZ,
                                order,
                                colliders,
                                onGroundForStep);
                collidedMovement = collision.movement();
                collisionWithoutStep = collision.withoutStep();
            } else {
                collidedMovement = collideLikeClient(
                        player,
                        result,
                        result.getSimulationContext().getFromActualPose(),
                        desiredX,
                        desiredY,
                        desiredZ,
                        order,
                        true,
                        colliders);
            }
            if (Math.abs(collidedMovement.y - result.getTarget().y * stuck.y) > STEP_Y_MATCH_EPSILON) {
                return false;
            }
            if (!requireSelectedStep) {
                return true;
            }
            if (result.getSimulationContext().isOnGround() && (desiredY >= 0.0D || collidedMovement.y == desiredY)) {
                return false;
            }

            if (collisionWithoutStep == null) {
                collisionWithoutStep = collideLikeClient(
                        player,
                        result,
                        result.getSimulationContext().getFromActualPose(),
                        desiredX,
                        desiredY,
                        desiredZ,
                        order,
                        false,
                        colliders);
            }
            return Collisions.getHorizontalDistanceSqr(collidedMovement)
                    > Collisions.getHorizontalDistanceSqr(collisionWithoutStep);
        }

        private static double[] axisSamples(double packetDelta, double minAttemptedDelta, double maxAttemptedDelta) {
            if (Math.abs(maxAttemptedDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                    return new double[] {packetDelta};
                }
                return new double[] {packetDelta, minAttemptedDelta};
            }
            if (Math.abs(packetDelta - minAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                return new double[] {packetDelta, maxAttemptedDelta};
            }
            if (Math.abs(packetDelta - maxAttemptedDelta) <= AXIS_SAMPLE_EPSILON) {
                return new double[] {packetDelta, minAttemptedDelta};
            }
            return new double[] {packetDelta, minAttemptedDelta, maxAttemptedDelta};
        }

        private static Vec3 collideLikeClient(
                CultPlayer player,
                PredictionResult result,
                SimpleCollisionBox box,
                double desiredX,
                double desiredY,
                double desiredZ,
                List<Collisions.Axis> order,
                boolean allowStepping,
                List<SimpleCollisionBox> colliders) {
            if (order == null) {
                return Collisions.collideWithAdditionalCollisionBoxes(
                        player, box, desiredX, desiredY, desiredZ, colliders);
            }

            return Collisions.collideWithAdditionalCollisionBoxes(
                    player,
                    box,
                    desiredX,
                    desiredY,
                    desiredZ,
                    order,
                    allowStepping,
                    colliders,
                    result.getSimulationContext().getLastOnGround().determineOptimistically());
        }
    }
}
