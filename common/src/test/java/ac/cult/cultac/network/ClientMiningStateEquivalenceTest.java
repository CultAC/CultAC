package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.blocksim.engine.SimFluidState;
import ac.cult.blocksim.interaction.MiningSpeed;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.ClientFluidQueries;
import ac.cult.cultac.utils.nmsutil.ClientMiningState;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The mining state's primitive reads and the eye query on primitive bounds against the box and vector forms they
 * replace, kept here verbatim: every pose, scales from negative to non-finite, coordinates from water blocks to NaN and
 * the world's edge, on each eye rule.
 */
class ClientMiningStateEquivalenceTest {
    private static final double[] SPECIAL = {
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        -0.0,
        0.0,
        1.0E300,
        -1.0E300,
        Double.MIN_VALUE,
        30_000_000.5,
        -30_000_000.5,
        Math.nextDown(1.0),
        0.999
    };
    static final float[] SCALES = {
        1.0F, 0.5F, 2.0F, 0.0625F, 16.0F, -1.0F, 0.0F, -0.0F, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE
    };

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_26_1", "V_26_2", "V_26_3"})
    void miningInputsMatchTheBoxedForm(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.water(new BlockPos(-1, FLOOR_Y, -1), new BlockPos(1, FLOOR_Y + 2, 1));
            CultPlayer player = f.player;
            Random random = new Random(version.ordinal());
            Pose[] poses = Pose.values();
            ClientMiningState.Inputs inputs = poisoned();
            int wet = 0, dry = 0, failed = 0;
            for (int i = 0; i < 20_000; i++) {
                player.x = coordinate(random, 0.0);
                player.y = height(random);
                player.z = coordinate(random, 0.0);
                player.pose = poses[random.nextInt(poses.length)];
                player.dimensionScale = random.nextInt(3) == 0 ? finiteHeightScale(random) : 1.0F;
                String where = version + " at " + player.x + " " + player.y + " " + player.z + " " + player.pose + " x"
                        + player.dimensionScale;
                Outcome expected = Outcome.of(() -> referenceState(player));
                assertEquals(expected, Outcome.of(() -> ClientMiningState.of(player)), where);
                assertEquals(expected, Outcome.of(() -> inputs.read(player).state()), where);
                if (expected.value == null) failed++;
                else if (((MiningSpeed.State) expected.value).eyesInWater()) wet++;
                else dry++;
            }
            assertTrue(wet > 500 && dry > 500, version + " wet " + wet + " dry " + dry + " failed " + failed);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_26_1", "V_26_3"})
    void theBoxEyeQueryMatchesTheCopiedBox(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.water(new BlockPos(-1, FLOOR_Y, -1), new BlockPos(1, FLOOR_Y + 2, 1));
            Random random = new Random(31 + version.ordinal());
            int wet = 0;
            for (int i = 0; i < 20_000; i++) {
                double minX = coordinate(random, 0.0), minY = height(random), minZ = coordinate(random, 0.0);
                var body = new SimpleCollisionBox(
                        minX,
                        minY,
                        minZ,
                        minX + random.nextDouble() * 2 - 0.5,
                        minY + random.nextDouble() * 3 - 0.5,
                        minZ + random.nextDouble() * 2 - 0.5,
                        random.nextBoolean());
                var feet = new Vec3(coordinate(random, 0.0), minY, coordinate(random, 0.0));
                double eyeY = random.nextInt(8) == 0 ? SPECIAL[random.nextInt(SPECIAL.length)] : minY + 1.62;
                var before = body.copy();
                boolean expected = referenceEyesInWater(f.player, body, feet, eyeY);
                assertEquals(
                        expected, ClientFluidQueries.eyesInWater(f.player, body, feet, eyeY), version + " " + body);
                assertEquals(
                        expected,
                        ClientFluidQueries.eyesInWater(
                                f.player, body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ, feet.x,
                                feet.z, eyeY),
                        version + " " + body);
                assertTrue(same(before, body), "body unchanged");
                if (expected) wet++;
            }
            assertTrue(wet > 200, version + " wet " + wet);
        }
    }

    private static ClientMiningState.Inputs poisoned() {
        var inputs = new ClientMiningState.Inputs();
        inputs.onGround = true;
        inputs.eyesInWater = true;
        inputs.efficiency = inputs.blockBreakSpeed = inputs.submergedSpeed = Double.NaN;
        inputs.haste = inputs.conduitPower = inputs.fatigue = 99;
        return inputs;
    }

    static void poison(SimpleCollisionBox box, Random random) {
        double value = random.nextBoolean() ? Double.NaN : -1.0E300;
        box.set(value, -value, value, -value, value, -value, true);
    }

    static double coordinate(Random random, double base) {
        return switch (random.nextInt(10)) {
            case 0 -> SPECIAL[random.nextInt(SPECIAL.length)];
            case 1 -> base + random.nextInt(5) - 2;
            default -> base + random.nextDouble() * 4 - 2;
        };
    }

    /** A feet height; the 26.1 eye scan steps an int up to the box top, which never ends above the int range. */
    private static double height(Random random) {
        double y = coordinate(random, FLOOR_Y);
        return y > Integer.MAX_VALUE ? 30_000_000.5 : y;
    }

    /** A scale that keeps the box top finite, for the same reason as {@link #height}. */
    private static float finiteHeightScale(Random random) {
        float scale = SCALES[random.nextInt(SCALES.length)];
        return Float.isInfinite(scale) ? 16.0F : scale;
    }

    static boolean same(SimpleCollisionBox a, SimpleCollisionBox b) {
        return bits(a.minX) == bits(b.minX)
                && bits(a.minY) == bits(b.minY)
                && bits(a.minZ) == bits(b.minZ)
                && bits(a.maxX) == bits(b.maxX)
                && bits(a.maxY) == bits(b.maxY)
                && bits(a.maxZ) == bits(b.maxZ)
                && a.isFullBlock() == b.isFullBlock();
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    // The forms replaced, verbatim.

    static MiningSpeed.State referenceState(CultPlayer player) {
        var self = player.compensatedEntities.getSelf();
        return new MiningSpeed.State(
                player.packetStateData.packetPlayerOnGround,
                referenceEyeInWater(player),
                self.miningEfficiency,
                self.blockBreakSpeed,
                self.submergedMiningSpeed,
                referenceAmplifier(player, MovementEffect.HASTE),
                referenceAmplifier(player, MovementEffect.CONDUIT_POWER),
                referenceAmplifier(player, MovementEffect.MINING_FATIGUE));
    }

    private static int referenceAmplifier(CultPlayer player, MovementEffect effect) {
        Integer level = player.compensatedEntities.getPotionLevelForPlayer(effect);
        return level == null ? -1 : level;
    }

    private static boolean referenceEyeInWater(CultPlayer player) {
        SimpleCollisionBox body = GetBoundingBox.getBoundingBoxFromPosAndSize(
                player.x,
                player.y,
                player.z,
                player.pose.width * player.dimensionScale,
                player.pose.height * player.dimensionScale);
        return referenceEyesInWater(
                player, body, new Vec3(player.x, player.y, player.z), player.y + player.getEyeHeight());
    }

    static boolean referenceEyesInWater(CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY) {
        if (!player.isBedrockMovement()) {
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_1)) {
                return referenceEyesInside(player, body, feet, eyeY, FluidTags.WATER);
            }
            if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_5)) {
                float level = (float) player.compensatedWorld.getWaterFluidLevelAt(feet.x, eyeY, feet.z);
                return (float) Math.floor(eyeY) + level > eyeY;
            }
        }
        double legacyEyeY = eyeY - 0.1111111119389534D;
        return legacyEyeY
                < (float) Math.floor(legacyEyeY)
                        + player.compensatedWorld.getWaterFluidLevelAt(feet.x, legacyEyeY, feet.z);
    }

    private static boolean referenceEyesInside(
            CultPlayer player, SimpleCollisionBox body, Vec3 feet, double eyeY, FluidTags tag) {
        SimpleCollisionBox box = body.copy().expand(-0.001D);
        int x = (int) Math.floor(feet.x), z = (int) Math.floor(feet.z);
        if (x < Math.floor(box.minX)
                || x >= Math.ceil(box.maxX)
                || z < Math.floor(box.minZ)
                || z >= Math.ceil(box.maxZ)) return false;
        if (!player.compensatedWorld.mayHaveFluid(
                x, (int) Math.floor(box.minY), z, x, (int) Math.ceil(box.maxY) - 1, z)) return false;
        for (int y = (int) Math.floor(box.minY); y < Math.ceil(box.maxY); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            SimFluidState fluid = ClientFluidQueries.fluidAt(player.compensatedWorld, pos);
            if (fluid.isEmpty() || !ClientFluidQueries.is(player, fluid, tag)) continue;
            float ordinaryHeight = ClientFluidQueries.height(player.compensatedWorld, pos, fluid);
            if ((double) y + ordinaryHeight < box.minY || eyeY < y) continue;
            BlockPos above = pos.above();
            float eyeHeight = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)
                            && fluid.isSource()
                            && player.compensatedWorld
                                    .geometry()
                                    .isFaceSturdy(
                                            player.compensatedWorld.getBlockStateIdAt(above),
                                            above,
                                            Direction.DOWN)
                    ? 1.0F
                    : ordinaryHeight;
            if (eyeY <= (double) y + eyeHeight) return true;
        }
        return false;
    }

    private record Outcome(Object value, String failure) {
        static Outcome of(Supplier<?> call) {
            try {
                return new Outcome(call.get(), null);
            } catch (RuntimeException e) {
                return new Outcome(null, e.getClass().getName());
            }
        }
    }
}
