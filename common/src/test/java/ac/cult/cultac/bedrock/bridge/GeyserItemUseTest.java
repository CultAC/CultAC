package ac.cult.cultac.bedrock.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.WorldCache;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class GeyserItemUseTest {
    private static Object previousGeyser;

    @BeforeClass
    public static void bootstrap() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var field = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        field.setAccessible(true);
        previousGeyser = field.get(null);
        field.set(null, mock(org.geysermc.geyser.GeyserImpl.class, RETURNS_DEEP_STUBS));
    }

    @AfterClass
    public static void restoreGeyser() throws Exception {
        var field = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, previousGeyser);
    }

    @Test
    public void airUseCompletesThroughTheBundledSimulator() {
        try (var h = new Harness()) {
            h.player
                    .getInventory()
                    .inventory
                    .setHeldItem(OfflineCultTestBootstrap.item("minecraft:firework_rocket", 3));
            h.session.getWorldCache().nextPredictionSequence();
            var packet = new InventoryTransactionPacket();
            packet.setTransactionType(InventoryTransactionType.ITEM_USE);
            packet.setActionType(1);
            GeyserItemUse.observe(h.session, h.player, packet, 0);
            assertEquals(Hand.MAIN_HAND, h.player.actionManager.getHand());
            assertEquals(3, h.player.getInventory().getHeldItem().getCount());
            assertFalse(h.player.packetStateData.isSlowedByUsingItem());
        }
    }

    @Test
    public void equipmentUseSwapsTheChestSlotExactlyOnce() {
        try (var h = new Harness()) {
            var inventory = h.player.getInventory().inventory;
            inventory.setHeldItem(OfflineCultTestBootstrap.item("minecraft:elytra"));
            inventory
                    .getInventoryStorage()
                    .setItem(
                            ac.cult.cultac.utils.inventory.Inventory.SLOT_CHESTPLATE,
                            OfflineCultTestBootstrap.item("minecraft:iron_chestplate"));
            h.session.getWorldCache().nextPredictionSequence();
            var packet = new InventoryTransactionPacket();
            packet.setTransactionType(InventoryTransactionType.ITEM_USE);
            packet.setActionType(1);
            GeyserItemUse.observe(h.session, h.player, packet, 0);
            assertTrue(inventory.getChestplate().is("minecraft:elytra"));
            assertTrue(inventory.getHeldItem().is("minecraft:iron_chestplate"));
        }
    }

    @Test
    public void repeatClickCanPlaceSandWithoutAJavaPredictionSequence() {
        try (var h = new Harness()) {
            // Capture 1790126982861: the failed use at 5.819 s advances Geyser's
            // sequence; the successful repeat at 5.869 s is filtered by Geyser.
            var support = new BlockPos(2369, 64, -41);
            h.seed(support);
            h.player.getInventory().inventory.getSlot(36).set((OfflineCultTestBootstrap.item("minecraft:sand", 61)));
            h.position(2369.6301, 65.75320, -40.47053);
            var click = useOn(support, 1);
            GeyserItemUse.observe(h.session, h.player, click, 0);
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));

            h.position(2369.6301, 66.00134, -40.471252);
            GeyserItemUse.observe(h.session, h.player, click, 0);
            assertTrue(DataTables.defaults()
                    .registry()
                    .block(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .key()
                    .equals("minecraft:sand"));

            // A Java acknowledgement alone cannot remove the local prediction.
            h.player.compensatedWorld.handlePredictionConfirmation(0, 5);
            h.player.latencyUtils.handleNettySyncTransaction(5);
            assertTrue(DataTables.defaults()
                    .registry()
                    .block(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .key()
                    .equals("minecraft:sand"));
            h.player.latencyUtils.addRealTimeTask(
                    6,
                    () -> h.player.compensatedWorld.handleServerBlockUpdate(
                            support.above(),
                            DataTables.defaults()
                                    .registry()
                                    .block("minecraft:air")
                                    .defaultState(),
                            6));
            assertTrue(DataTables.defaults()
                    .registry()
                    .block(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .key()
                    .equals("minecraft:sand"));
            h.player.latencyUtils.handleNettySyncTransaction(6);
            assertTrue(DataTables.defaults()
                    .registry()
                    .facts(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                    .has(ac.cult.blocksim.data.StateFacts.AIR));
        }
    }

    @Test
    public void repeatDoorUseTogglesBothHalvesEvenWhenGeyserFiltersIt() {
        try (var h = new Harness()) {
            var support = new BlockPos(2363, 63, -34);
            h.seed(support);
            var lower = support.above();
            var door =
                    DataTables.defaults().registry().block("minecraft:oak_door").defaultState();
            h.player.compensatedWorld.updateBlock(lower.getX(), lower.getY(), lower.getZ(), door);
            h.player.compensatedWorld.updateBlock(
                    lower.getX(),
                    lower.getY() + 1,
                    lower.getZ(),
                    DataTables.defaults().registry().with(door, "half", "upper"));
            h.position(2363.5, 64, -35);
            for (int i = 0; i < 2; i++) {
                GeyserItemUse.observe(h.session, h.player, useOn(lower, 2), 0);
                assertEquals(i == 0, BlockProps.OPEN.booleanValue(h.player.compensatedWorld.getBlockStateIdAt(lower)));
                assertEquals(
                        i == 0,
                        BlockProps.OPEN.booleanValue(h.player.compensatedWorld.getBlockStateIdAt(lower.above())));
            }
        }
    }

    @Test
    public void placementUsesCurrentPositionInsteadOfStaleMovementBox() {
        for (var item : new String[] {"minecraft:sand", "minecraft:dirt"}) {
            try (var h = new Harness()) {
                var support = new BlockPos(2292, 62, -84);
                h.seed(support);
                h.player.getInventory().inventory.getSlot(36).set((OfflineCultTestBootstrap.item(item, 22)));
                // Capture 1790130256806, tick 4842: the next block overlaps the
                // current player, even if the movement box still trails behind.
                h.position(2292.6438, 63, -82.84734);
                var staleBox = GetBoundingBox.getCollisionBoxForPlayer(h.player, 2292.5, 63, -82.5);
                h.player.boundingBox = staleBox;
                GeyserItemUse.observe(h.session, h.player, useOn(support, 1), 0);
                assertTrue(DataTables.defaults()
                        .registry()
                        .facts(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                        .has(ac.cult.blocksim.data.StateFacts.AIR));
                assertSame(staleBox, h.player.boundingBox);

                // Conversely, a stale overlapping box must not reject a legal placement.
                h.position(2292.5, 63, -82.5);
                staleBox = GetBoundingBox.getCollisionBoxForPlayer(h.player, 2292.6438, 63, -82.84734);
                h.player.boundingBox = staleBox;
                GeyserItemUse.observe(h.session, h.player, useOn(support, 1), 0);
                assertTrue(DataTables.defaults()
                        .registry()
                        .block(h.player.compensatedWorld.getBlockStateIdAt(support.above()))
                        .key()
                        .equals(item));
                assertSame(staleBox, h.player.boundingBox);
            }
        }
    }

    private static InventoryTransactionPacket useOn(BlockPos pos, int face) {
        var packet = new InventoryTransactionPacket();
        packet.setTransactionType(InventoryTransactionType.ITEM_USE);
        packet.setActionType(0);
        packet.setBlockPosition(Vector3i.from(pos.getX(), pos.getY(), pos.getZ()));
        packet.setBlockFace(face);
        packet.setClickPosition(Vector3f.from(.63f, 1, .53f));
        // Deliberately do not supply a claimed result or item: prediction must derive
        // placement from the compensated inventory, world and player collision box.
        return packet;
    }

    private static final class Harness implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final GeyserSession session = mock(GeyserSession.class, RETURNS_DEEP_STUBS);
        final CultPlayer player;

        Harness() {
            UUID uuid = UUID.randomUUID();
            var connection = new ac.cult.cultac.network.CultConnection(
                    OfflineCultTestBootstrap.platformConnection(),
                    channel,
                    ac.cult.cultac.CultAPI.INSTANCE.getNetworkManager().dispatcher(),
                    ignored -> null);
            for (var direction : ac.cult.cultac.protocol.PacketDirection.values()) {
                connection.phase(direction, ac.cult.cultac.protocol.ConnectionPhase.PLAY);
            }
            player = new CultPlayer(
                    new User(new User.Profile(uuid, ".Placement_Replay"), connection),
                    MovementPlatform.BEDROCK,
                    new BedrockPlayerState(uuid));
            player.gamemode = GameMode.SURVIVAL;
            var cache = new WorldCache(session);
            when(session.getWorldCache()).thenReturn(cache);
        }

        void seed(BlockPos pos) {
            player.compensatedWorld.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            player.compensatedWorld.updateBlock(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    DataTables.defaults().registry().block("minecraft:sand").defaultState());
        }

        void position(double x, double y, double z) {
            player.x = x;
            player.y = y;
            player.z = z;
            player.packetStateData.clientSidePosition = new Vec3(x, y, z);
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
        }

        @Override
        public void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            channel.close();
        }
    }
}
