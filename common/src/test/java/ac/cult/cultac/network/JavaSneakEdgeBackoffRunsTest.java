package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.UnknownCollisionProvider;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.SneakEdgeBackoff;
import java.lang.reflect.Method;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * SneakEdgeBackoff#backOff, which passes runs of 0.05 steps whose probes share one box that can fall, ends where
 * Player#maybeBackOffFromEdge's step-by-step loops do, and a server-sent delta no longer costs a probe per step.
 */
class JavaSneakEdgeBackoffRunsTest {
    private static final BlockDefinition[] SHAPES = {
        BlockIds.STONE, BlockIds.GLASS_PANE, BlockIds.IRON_BARS, BlockIds.ANVIL, BlockIds.CAULDRON, BlockIds.SNOW,
        BlockIds.SOUL_SAND, BlockIds.FARMLAND, BlockIds.SCAFFOLDING, BlockIds.POWDER_SNOW, BlockIds.POINTED_DRIPSTONE,
        BlockIds.TURTLE_EGG, BlockIds.LECTERN, BlockIds.CHEST, BlockIds.PLAYER_HEAD, BlockIds.LADDER
    };

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_4", "V_26_3"})
    void runsEndWhereTheStepByStepLoopsDo(ClientVersion version) throws Exception {
        // canFallAtLeast holds inside a box where it holds: a moving piston on a probe's double edge is large.
        var registry = DataTables.defaults().registry();
        BlockDefinition piston = BlockIds.MOVING_PISTON;
        for (int state = piston.firstState(); state < piston.firstState() + piston.stateCount(); state++)
            assertTrue(registry.facts(state).has(StateFacts.LARGE_COLLISION), "moving piston " + state);
        Method backOff = backOff();
        Random random = new Random(version.ordinal());
        for (int world = 0; world < 6; world++) {
            try (var f = new JavaTickFixture()) {
                JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
                for (int x = -24; x <= 24; x++) {
                    for (int z = -24; z <= 24; z++) {
                        for (int y = 61; y <= 65; y++) {
                            int roll = random.nextInt(100);
                            int state = y == 63 && roll < 35
                                    ? BlockIds.STONE.defaultState()
                                    : roll < 6
                                            ? SHAPES[random.nextInt(SHAPES.length)].defaultState()
                                            : BlockIds.AIR.defaultState();
                            f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), state);
                        }
                    }
                }
                for (int i = 0; i < 6; i++)
                    f.player.compensatedEntities.addEntity(
                            900 + i,
                            EntityTypeIds.OAK_BOAT,
                            new Vec3(random.nextInt(30) - 15 + 0.5, 63, random.nextInt(30) - 15 + 0.5),
                            0,
                            0,
                            0);
                for (int sample = 0; sample < 1_500; sample++) {
                    double x = random.nextDouble() * 20 - 10, z = random.nextDouble() * 20 - 10;
                    double y = 64 + (random.nextInt(4) == 0 ? random.nextInt(3) * 0.0625 - 0.0625 : 0);
                    SimpleCollisionBox box = new SimpleCollisionBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.5, z + 0.3);
                    double size = random.nextInt(3) == 0 ? 0.3 : random.nextInt(2) == 0 ? 3 : 30;
                    Vec3 delta = new Vec3(
                            random.nextInt(5) == 0 ? 0.0 : (random.nextDouble() * 2 - 1) * size,
                            -random.nextDouble() * 0.1,
                            random.nextInt(5) == 0 ? 0.0 : (random.nextDouble() * 2 - 1) * size);
                    float maxDownStep = 0.6F;
                    Vec3 expected = stepByStep(f.player, box, delta, maxDownStep);
                    Vec3 actual = (Vec3) backOff.invoke(null, f.player, box, delta, maxDownStep);
                    assertEquals(
                            Double.doubleToRawLongBits(expected.x),
                            Double.doubleToRawLongBits(actual.x),
                            "x of " + delta + " from " + box);
                    assertEquals(
                            Double.doubleToRawLongBits(expected.z),
                            Double.doubleToRawLongBits(actual.z),
                            "z of " + delta + " from " + box);
                    assertEquals(expected.y, actual.y);
                }
            }
        }
    }

    /** A plugin's set_entity_motion carries up to 1.7E10 a tick; standing on a pillar, the loops end in moments. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_3"})
    void aServerSentDeltaDoesNotProbeEveryStep(ClientVersion version) throws Exception {
        Method backOff = backOff();
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++)
                for (int z = -8; z <= 8; z++)
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.AIR.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(0, 63, 0), BlockIds.STONE.defaultState());
            SimpleCollisionBox box = new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
            Vec3 small = new Vec3(40, -0.0784, -25);
            assertEquals(stepByStep(f.player, box, small, 0.6F), backOff.invoke(null, f.player, box, small, 0.6F));
            // Probing each of the 2E7 steps of a million-block delta took seconds; 3.4E11 steps took hours.
            for (Vec3 delta : new Vec3[] {new Vec3(1.0E6, -0.0784, 0), new Vec3(1.0E6, -0.0784, -1.7E10)}) {
                long start = System.nanoTime();
                Vec3 backedOff = (Vec3) backOff.invoke(null, f.player, box, delta, 0.6F);
                long elapsed = System.nanoTime() - start;
                assertTrue(elapsed < 500_000_000L, delta + " took " + elapsed + " ns");
                assertTrue(Math.abs(backedOff.x) < 1.0 && Math.abs(backedOff.z) < 1.0, "backed off to " + backedOff);
            }
        }
    }

    private static Method backOff() throws Exception {
        Method method = SneakEdgeBackoff.class.getDeclaredMethod(
                "backOff", CultPlayer.class, SimpleCollisionBox.class, Vec3.class, float.class);
        method.setAccessible(true);
        return method;
    }

    /** Player#maybeBackOffFromEdge's loops, one canFallAtLeast per 0.05 step. */
    private static Vec3 stepByStep(CultPlayer player, SimpleCollisionBox box, Vec3 delta, float maxDownStep) {
        double deltaX = delta.x;
        double deltaZ = delta.z;
        double stepX = Math.signum(deltaX) * 0.05;
        double stepZ = Math.signum(deltaZ) * 0.05;
        while (deltaX != 0.0 && canFallAtLeast(player, box, deltaX, 0.0, maxDownStep)) {
            if (Math.abs(deltaX) <= 0.05) {
                deltaX = 0.0;
                break;
            }
            deltaX -= stepX;
        }
        while (deltaZ != 0.0 && canFallAtLeast(player, box, 0.0, deltaZ, maxDownStep)) {
            if (Math.abs(deltaZ) <= 0.05) {
                deltaZ = 0.0;
                break;
            }
            deltaZ -= stepZ;
        }
        while (deltaX != 0.0 && deltaZ != 0.0 && canFallAtLeast(player, box, deltaX, deltaZ, maxDownStep)) {
            deltaX = Math.abs(deltaX) <= 0.05 ? 0.0 : deltaX - stepX;
            deltaZ = Math.abs(deltaZ) <= 0.05 ? 0.0 : deltaZ - stepZ;
        }
        return new Vec3(deltaX, delta.y, deltaZ);
    }

    private static boolean canFallAtLeast(
            CultPlayer player, SimpleCollisionBox box, double deltaX, double deltaZ, double minHeight) {
        boolean modern = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5);
        double horizontalMargin = modern ? 1.0E-7 : 0.0;
        double verticalMargin = modern ? 1.0E-7 : (double) 1.0E-5F;
        SimpleCollisionBox probe = new SimpleCollisionBox(
                box.minX + horizontalMargin + deltaX,
                box.minY - minHeight - verticalMargin,
                box.minZ + horizontalMargin + deltaZ,
                box.maxX - horizontalMargin + deltaX,
                box.minY,
                box.maxZ - horizontalMargin + deltaZ);
        return Collisions.isEmpty(player, probe, box.minY)
                && !UnknownCollisionProvider.hasEntityCollision(player, Collisions.feet(box), probe);
    }
}
