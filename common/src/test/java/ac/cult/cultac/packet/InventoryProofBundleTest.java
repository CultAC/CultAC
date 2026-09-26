package ac.cult.cultac.packet;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import ac.cult.cultac.utils.latency.LatencyUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.Items;
import org.bukkit.Material;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public final class InventoryProofBundleTest {
    @BeforeClass
    public static void bootstrap() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var globalType = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
        var global = globalType.getDeclaredMethod("get").invoke(null);
        var misc = globalType.getField("misc");
        if (misc.get(global) == null) {
            var miscType = Class.forName("io.papermc.paper.configuration.GlobalConfiguration$Misc");
            misc.set(global, miscType.getConstructor(globalType).newInstance(global));
        }
    }

    @Test
    public void standaloneUpdateAndProofReachClientInOneBundleAndApplyOnlyOnAck() {
        try (var fixture = new Fixture(true, false)) {
            var update = slot(36, 1);
            assertTrue(fixture.channel.writeOutbound(update));
            Packet<?> output = fixture.channel.readOutbound();
            assertTrue("Update and proof must share a client processing operation", output instanceof ClientboundBundlePacket);
            var children = PacketBundleUtil.flattenOneLevel(output);
            assertEquals(2, children.size());
            assertSame(update, children.get(0));
            assertSame(fixture.proofs.getFirst().packet(), children.get(1));
            assertNull("No proof may be written outside the bundle", fixture.channel.readOutbound());
            assertEquals(Material.AIR, fixture.inventory.inventory.getSlot(36).getItem().getType());

            var callback = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.player.latencyUtils).addRealTimeTask(eq(1), callback.capture());
            callback.getValue().run();
            assertEquals(Material.STONE, fixture.inventory.inventory.getSlot(36).getItem().getType());
            verify(fixture.player).markTrackedTransactionPacketSent(fixture.proofs.getFirst());
        }
    }

    @Test
    public void existingBundleKeepsChildOrderAndContainsEveryInventoryProof() {
        try (var fixture = new Fixture(true, false)) {
            var first = slot(36, 1);
            var second = slot(37, 2);
            var unrelated = new ClientboundPingPacket(900);
            assertTrue(fixture.channel.writeOutbound(new ClientboundBundlePacket(List.of(first, unrelated, second))));
            Packet<?> output = fixture.channel.readOutbound();
            assertTrue(output instanceof ClientboundBundlePacket);
            assertEquals(List.of(first, fixture.proofs.get(0).packet(), unrelated,
                            second, fixture.proofs.get(1).packet()),
                    PacketBundleUtil.flattenOneLevel(output));
            assertNull(fixture.channel.readOutbound());
        }
    }

    @Test
    public void legacyAndBedrockTransportsKeepTheirExistingDeferredProof() {
        for (boolean bedrock : List.of(false, true)) {
            try (var fixture = new Fixture(bedrock, bedrock)) {
                var update = slot(36, 1);
                assertTrue(fixture.channel.writeOutbound(update));
                assertSame(update, fixture.channel.readOutbound());
                assertSame(fixture.proofs.getFirst().packet(), fixture.channel.readOutbound());
                assertNull(fixture.channel.readOutbound());
                verify(fixture.player).markTrackedTransactionPacketSent(fixture.proofs.getFirst());
            }
        }
    }

    private static ClientboundContainerSetSlotPacket slot(int slot, int count) {
        return new ClientboundContainerSetSlotPacket(0, 1, slot,
                new net.minecraft.world.item.ItemStack(Items.STONE, count));
    }

    private static final class Fixture implements AutoCloseable {
        private final CultPlayer player = mock(CultPlayer.class);
        private final CompensatedInventory inventory;
        private final EmbeddedChannel channel;
        private final List<CultPlayer.TrackedTransaction> proofs = new ArrayList<>();

        private Fixture(boolean bundles, boolean bedrock) {
            var connection = mock(Connection.class);
            channel = new EmbeddedChannel();
            try {
                var user = new User(new User.Profile(java.util.UUID.randomUUID(), "InventoryProofTest"),
                        null, null, connection, channel);
                var userField = CultPlayer.class.getField("user");
                userField.setAccessible(true);
                userField.set(player, user);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            player.packetStateData = new PacketStateData();
            player.latencyUtils = mock(LatencyUtils.class);
            when(player.supportsBundles()).thenReturn(bundles);
            when(player.isBedrockMovement()).thenReturn(bedrock);
            var sequence = new AtomicInteger();
            when(player.createTrackedTransactionPacketForDeferredSend()).thenAnswer(ignored -> {
                int id = sequence.incrementAndGet();
                var proof = new CultPlayer.TrackedTransaction(id, id, new ClientboundPingPacket(id));
                proofs.add(proof);
                return proof;
            });
            inventory = new CompensatedInventory(player);
            var api = new PacketApi("inventory-proof-test", false);
            api.registerHandler(ClientboundContainerSetSlotPacket.class, Connection.class,
                    PacketApi.DEFAULT_HANDLER_PRIORITY,
                    (PacketContextHandlerFunction<Connection, ClientboundContainerSetSlotPacket>) (context, receiver, packet) -> {
                        var event = new PacketSendEvent(player.user, packet, ConnectionProtocol.PLAY, context.insideBundle());
                        inventory.onContainerSetSlot(event, player, packet);
                        // The Cult bridge schedules these after forwarding the returned group.
                        context.channel().eventLoop().execute(() -> event.getTasksAfterSend().forEach(Runnable::run));
                        var output = new ArrayList<Packet<?>>();
                        output.addAll(event.getPacketsBeforeSend());
                        output.add(event.getNmsPacket());
                        output.addAll(event.getPacketsAfterSend());
                        return output;
                    });
            channel.pipeline().addLast(new ChannelPacketHandler(api, connection));
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }
}
