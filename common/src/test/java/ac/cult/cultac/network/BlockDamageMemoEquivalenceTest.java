package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.breaking.BlockDamageMemo;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * The memo against BlockBreakSpeed#getBlockDamage at every call of a random sequence that changes one input at a time
 * and often none: block, held stack (replaced, recounted or re-componented in place), ground, eye in water,
 * attributes, effects and game mode, on clients with both Mining Fatigue forms.
 */
class BlockDamageMemoEquivalenceTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static final BlockPos EYE = new BlockPos(0, 65, 0);
    private static final String[] BLOCKS = {
        "minecraft:obsidian", "minecraft:stone", "minecraft:dirt", "minecraft:barrier", "minecraft:mushroom_stem",
        "minecraft:glass", "minecraft:air", "minecraft:oak_log", "minecraft:cobweb", "minecraft:bedrock"
    };
    private static final String[] ITEMS = {
        "minecraft:diamond_pickaxe", "minecraft:netherite_axe", "minecraft:wooden_shovel", "minecraft:stick",
        "minecraft:shears", "minecraft:diamond_sword", "minecraft:golden_hoe", "minecraft:air"
    };
    private static final double[] EFFICIENCY = {0.0, 26.0, 100.0, -0.0, 1024.0};
    private static final double[] BREAK_SPEED = {1.0, 0.5, 0.0, 2.0};
    private static final double[] SUBMERGED = {0.2, 1.0, 0.0};
    private static final MovementEffect[] EFFECTS = {
        MovementEffect.HASTE, MovementEffect.CONDUIT_POWER, MovementEffect.MINING_FATIGUE
    };
    private static final GameMode[] MODES = {GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.CREATIVE};

    @Test
    void everyCallMatchesTheDirectComputation() throws Exception {
        // Bedrock movement takes the table Mining Fatigue form of clients before 26.3.
        for (MovementPlatform platform : List.of(MovementPlatform.JAVA, MovementPlatform.BEDROCK)) {
            for (long seed = 1; seed <= 3; seed++) {
                assertTrue(run(platform, seed, 3000), platform + " computed no damage");
            }
        }
    }

    @Test
    void aNullStackFailsAsTheDirectComputationDoes() throws Exception {
        try (var services = new RecordConsumerServices();
                var record = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            record.user.bind(mock(PlatformPlayer.class));
            CultPlayer player = record.player;
            int stone = state("minecraft:stone");
            var memo = new BlockDamageMemo();
            Class<? extends Throwable> direct =
                    assertThrows(RuntimeException.class, () -> BlockBreakSpeed.getBlockDamage(player, null, stone))
                            .getClass();
            assertEquals(direct, assertThrows(RuntimeException.class, () -> memo.damage(player, null, stone))
                    .getClass());
            player.gamemode = GameMode.CREATIVE;
            assertEquals(
                    BlockBreakSpeed.getBlockDamage(player, null, stone), memo.damage(player, null, stone));
        }
    }

    /** The raw bits of the damage, or the failure's class and message prefixed with '!'. */
    private static String outcome(DoubleSupplier damage) {
        try {
            return Long.toHexString(Double.doubleToRawLongBits(damage.getAsDouble()));
        } catch (RuntimeException failure) {
            return "!" + failure.getClass().getName() + ": " + failure.getMessage();
        }
    }

    /** True when some call computed a damage rather than failing. */
    private static boolean run(MovementPlatform version, long seed, int steps) throws Exception {
        try (var services = new RecordConsumerServices();
                var record = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            record.user.bind(mock(PlatformPlayer.class));
            CultPlayer player = record.player;
            player.movementPlatform = version;
            player.gamemode = GameMode.SURVIVAL;
            player.x = 0.5;
            player.y = 64;
            player.z = 0.5;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            var random = new Random(seed);
            var memo = new BlockDamageMemo();
            int water = state("minecraft:water");
            int air = state("minecraft:air");
            player.getInventory().inventory.setHeldItem(OfflineCultTestBootstrap.item(ITEMS[0]));
            player.compensatedWorld.updateBlock(TARGET, state(BLOCKS[0]));
            int unchanged = 0, distinct = 0, failed = 0;
            String lastOutcome = "";
            for (int step = 0; step < steps; step++) {
                int change = random.nextInt(24);
                switch (change) {
                    case 0 -> player.compensatedWorld.updateBlock(TARGET, state(BLOCKS[random.nextInt(BLOCKS.length)]));
                    case 1 -> player.getInventory()
                            .inventory
                            .setHeldItem(OfflineCultTestBootstrap.item(
                                    ITEMS[random.nextInt(ITEMS.length)], 1 + random.nextInt(3)));
                    case 2 -> {
                        if (owned(player)) held(player).setCount(random.nextInt(3));
                    }
                    case 3 -> {
                        if (owned(player)) held(player).removeComponent("minecraft:tool");
                    }
                    case 4 -> player.packetStateData.packetPlayerOnGround = !player.packetStateData.packetPlayerOnGround;
                    case 5 -> player.compensatedWorld.updateBlock(EYE, random.nextBoolean() ? water : air);
                    case 6 -> attribute(player, "minecraft:mining_efficiency", pick(random, EFFICIENCY));
                    case 7 -> attribute(player, "minecraft:block_break_speed", pick(random, BREAK_SPEED));
                    case 8 -> attribute(player, "minecraft:submerged_mining_speed", pick(random, SUBMERGED));
                    case 9 -> {
                        var self = player.compensatedEntities.getSelf();
                        var effect = EFFECTS[random.nextInt(EFFECTS.length)];
                        if (random.nextBoolean()) self.addPotionEffect(effect, random.nextInt(4));
                        else self.removePotionEffect(effect);
                    }
                    case 10 -> player.gamemode = MODES[random.nextInt(MODES.length)];
                    default -> unchanged++;
                }
                int block = player.compensatedWorld.getBlockStateIdAt(TARGET);
                SimItemStack held = player.getInventory().getHeldItem();
                String expected = outcome(() -> BlockBreakSpeed.getBlockDamage(player, held, block));
                String actual = outcome(() -> memo.damage(player, held, block));
                assertEquals(expected, actual, () -> version + " seed " + seed + " step");
                if (expected.startsWith("!")) failed++;
                else if (!expected.equals(lastOutcome)) distinct++;
                lastOutcome = expected;
            }
            assertTrue(unchanged > steps / 2, "repeat calls " + unchanged);
            if (failed < steps) assertTrue(distinct > steps / 20, version + " distinct answers " + distinct);
            return failed < steps;
        }
    }

    private static SimItemStack held(CultPlayer player) {
        return player.getInventory().getHeldItem();
    }

    /** Only a stack this test created is changed in place; the shared empty stack is never written. */
    private static boolean owned(CultPlayer player) {
        SimItemStack held = held(player);
        return held != null && held != SimItemStack.empty() && held.definition() != SimItemStack.empty().definition();
    }

    private static double pick(Random random, double[] values) {
        return values[random.nextInt(values.length)];
    }

    private static void attribute(CultPlayer player, String key, double value) {
        player.compensatedEntities.updateAttributes(
                player.entityID, List.of(new AttributeSnapshot(key, value, List.of())));
    }

    private static int state(String name) {
        return DataTables.defaults().registry().block(name).defaultState();
    }
}
