package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.WorldCache;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GeyserItemUseTest {
    private static Object previousGeyser;
    @BeforeClass public static void bootstrap() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var field = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        field.setAccessible(true);
        previousGeyser = field.get(null);
        field.set(null, mock(org.geysermc.geyser.GeyserImpl.class, RETURNS_DEEP_STUBS));
    }
    @AfterClass public static void restoreGeyser() throws Exception {
        var field = org.geysermc.geyser.GeyserImpl.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, previousGeyser);
    }

    @Test public void repeatClickCanPlaceSandWithoutAJavaPredictionSequence() {
        try (var h = new Harness()) {
            // Capture 1790126982861: the failed use at 5.819 s advances Geyser's
            // sequence; the successful repeat at 5.869 s is filtered by Geyser.
            var support = new BlockPos(2369, 64, -41);
            h.seed(support);
            h.player.getInventory().inventory.getSlot(36).set(CraftItemStack.asBukkitCopy(
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SAND, 61)));
            h.position(2369.6301, 65.75320, -40.47053);
            var click = useOn(support, 1);
            GeyserItemUse.observe(h.session, h.player, click, 0);
            assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).isAir());

            h.position(2369.6301, 66.00134, -40.471252);
            GeyserItemUse.observe(h.session, h.player, click, 0);
            assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).is(Blocks.SAND));

            // A Java acknowledgement alone cannot remove the native local prediction.
            h.player.compensatedWorld.handlePredictionConfirmation(0, 5);
            h.player.latencyUtils.handleNettySyncTransaction(5);
            assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).is(Blocks.SAND));
            h.player.latencyUtils.addRealTimeTask(6, () -> h.player.compensatedWorld
                    .handleServerBlockUpdate(support.above(), Blocks.AIR.defaultBlockState(), 6));
            assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).is(Blocks.SAND));
            h.player.latencyUtils.handleNettySyncTransaction(6);
            assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).isAir());
        }
    }

    @Test public void repeatDoorUseTogglesBothHalvesEvenWhenGeyserFiltersIt() {
        try (var h = new Harness()) {
            var support = new BlockPos(2363, 63, -34);
            h.seed(support);
            var lower = support.above();
            var door = Blocks.OAK_DOOR.defaultBlockState();
            h.player.compensatedWorld.updateBlock(lower.getX(), lower.getY(), lower.getZ(), door);
            h.player.compensatedWorld.updateBlock(lower.getX(), lower.getY() + 1, lower.getZ(),
                    door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
            h.position(2363.5, 64, -35);
            for (int i = 0; i < 2; i++) {
                GeyserItemUse.observe(h.session, h.player, useOn(lower, 2), 0);
                assertEquals(i == 0, h.player.compensatedWorld.getBlockStateAt(lower).getValue(BlockStateProperties.OPEN));
                assertEquals(i == 0, h.player.compensatedWorld.getBlockStateAt(lower.above()).getValue(BlockStateProperties.OPEN));
            }
        }
    }

    @Test public void placementUsesCurrentPositionInsteadOfStaleMovementBox() {
        for (var item : new net.minecraft.world.item.Item[]{
                net.minecraft.world.item.Items.SAND, net.minecraft.world.item.Items.DIRT}) {
            try (var h = new Harness()) {
                var support = new BlockPos(2292, 62, -84);
                h.seed(support);
                h.player.getInventory().inventory.getSlot(36).set(CraftItemStack.asBukkitCopy(
                        new net.minecraft.world.item.ItemStack(item, 22)));
                // Capture 1790130256806, tick 4842: the next block overlaps the
                // current player, even if the movement box still trails behind.
                h.position(2292.6438, 63, -82.84734);
                var staleBox = GetBoundingBox.getCollisionBoxForPlayer(h.player, 2292.5, 63, -82.5);
                h.player.boundingBox = staleBox;
                GeyserItemUse.observe(h.session, h.player, useOn(support, 1), 0);
                assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above()).isAir());
                assertSame(staleBox, h.player.boundingBox);

                // Conversely, a stale overlapping box must not reject a legal placement.
                h.position(2292.5, 63, -82.5);
                staleBox = GetBoundingBox.getCollisionBoxForPlayer(h.player, 2292.6438, 63, -82.84734);
                h.player.boundingBox = staleBox;
                GeyserItemUse.observe(h.session, h.player, useOn(support, 1), 0);
                assertTrue(h.player.compensatedWorld.getBlockStateAt(support.above())
                        .is(((net.minecraft.world.item.BlockItem) item).getBlock()));
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
        final GeyserSession session = mock(GeyserSession.class);
        final CultPlayer player;
        Harness() {
            UUID uuid = UUID.randomUUID();
            player = new CultPlayer(new User(new User.Profile(uuid, ".Placement_Replay"), null, null, null, channel),
                    MovementPlatform.BEDROCK, new BedrockPlayerState(uuid));
            player.gamemode = GameMode.SURVIVAL;
            var cache = new WorldCache(session);
            when(session.getWorldCache()).thenReturn(cache);
        }
        void seed(BlockPos pos) {
            player.compensatedWorld.ensureValidationChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            player.compensatedWorld.updateBlock(pos.getX(), pos.getY(), pos.getZ(), Blocks.SAND.defaultBlockState());
        }
        void position(double x, double y, double z) {
            player.x = x; player.y = y; player.z = z;
            player.packetStateData.clientSidePosition = new Vec3(x, y, z);
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
        }
        @Override public void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            channel.close();
        }
    }
}
