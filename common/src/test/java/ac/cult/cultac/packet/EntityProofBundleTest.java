package ac.cult.cultac.packet;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketBundlePacker;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class EntityProofBundleTest {
    @BeforeClass
    public static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    @Test
    public void vehicleProofAndEntityUpdateArriveInOneWireBundle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientboundRemoveEntitiesPacket update = new ClientboundRemoveEntitiesPacket(5);
            assertTrue(fixture.server.writeOutbound(update));
            List<Packet<?>> received = fixture.receiveWirePackets();
            assertEquals(1, received.size());
            assertTrue(received.getFirst() instanceof ClientboundBundlePacket);
            assertEquals(List.of(update, fixture.proofs.getFirst().packet()),
                    PacketBundleUtil.flattenOneLevel(received.getFirst()));
        }
    }

    @Test
    public void vehicleProofKeepsAnEnclosingVanillaBundleIntact() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ClientboundKeepAlivePacket before = new ClientboundKeepAlivePacket(11L);
            ClientboundRemoveEntitiesPacket update = new ClientboundRemoveEntitiesPacket(5);
            ClientboundKeepAlivePacket after = new ClientboundKeepAlivePacket(12L);
            assertTrue(fixture.server.writeOutbound(new ClientboundBundlePacket(List.of(before, update, after))));
            List<Packet<?>> received = fixture.receiveWirePackets();
            assertEquals(1, received.size());
            assertTrue(received.getFirst() instanceof ClientboundBundlePacket);
            assertEquals(List.of(before, update, fixture.proofs.getFirst().packet(), after),
                    PacketBundleUtil.flattenOneLevel(received.getFirst()));
        }
    }

    @Test
    public void directResyncUsesBundlesOnlyForSupportedClients() throws Exception {
        for (boolean supportsBundles : new boolean[]{true, false}) {
            try (Fixture fixture = new Fixture(supportsBundles)) {
                ClientboundKeepAlivePacket update = new ClientboundKeepAlivePacket(11L);
                var proof = new CultPlayer.TrackedTransaction(10, 10, new ClientboundPingPacket(10));
                var write = PacketEntityReplication.class.getDeclaredMethod(
                        "writeProofGroupNow", CultPlayer.TrackedTransaction.class, List.class);
                write.setAccessible(true);
                write.invoke(fixture.replication, proof, List.of(update));

                List<Packet<?>> received = fixture.receiveWirePackets();
                if (supportsBundles) {
                    assertEquals(1, received.size());
                    assertTrue(received.getFirst() instanceof ClientboundBundlePacket);
                    assertEquals(List.of(update, proof.packet()), PacketBundleUtil.flattenOneLevel(received.getFirst()));
                } else {
                    assertEquals(List.of(update, proof.packet()), received);
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmbeddedChannel server = new EmbeddedChannel();
        private final EmbeddedChannel client;
        private final PacketEntityReplication replication;
        private final List<CultPlayer.TrackedTransaction> proofs = new ArrayList<>();

        private Fixture() throws ReflectiveOperationException {
            this(true);
        }

        private Fixture(boolean supportsBundles) throws ReflectiveOperationException {
            CultPlayer player = mock(CultPlayer.class);
            Connection connection = mock(Connection.class);
            User user = new User(new User.Profile(UUID.randomUUID(), "EntityProofTest"),
                    null, null, connection, server);
            var userField = CultPlayer.class.getField("user");
            userField.setAccessible(true);
            userField.set(player, user);
            when(player.supportsBundles()).thenReturn(supportsBundles);
            AtomicInteger sequence = new AtomicInteger();
            when(player.createTrackedTransactionPacketForBundle()).thenAnswer(ignored -> {
                int id = sequence.incrementAndGet();
                var proof = new CultPlayer.TrackedTransaction(id, id, new ClientboundPingPacket(id));
                proofs.add(proof);
                return proof;
            });
            replication = new PacketEntityReplication(player);
            BundlerInfo bundler = BundlerInfo.createForPacket(GamePacketTypes.CLIENTBOUND_BUNDLE,
                    ClientboundBundlePacket::new, new ClientboundBundleDelimiterPacket());
            server.pipeline().addLast(new PacketBundleUnpacker(bundler));
            client = new EmbeddedChannel(new PacketBundlePacker(bundler));
            PacketApi api = new PacketApi("entity-proof-test", false);
            api.registerHandler(ClientboundRemoveEntitiesPacket.class, Connection.class,
                    PacketApi.DEFAULT_HANDLER_PRIORITY,
                    (PacketContextHandlerFunction<Connection, ClientboundRemoveEntitiesPacket>) (context, receiver, packet) -> {
                        PacketSendEvent event = new PacketSendEvent(user, packet, ConnectionProtocol.PLAY, context.insideBundle());
                        replication.appendVehicleProtocolProofTransaction(event);
                        List<Packet<?>> result = new ArrayList<>(event.getPacketsBeforeSend());
                        result.add(event.getNmsPacket());
                        result.addAll(event.getPacketsAfterSend());
                        return result;
                    });
            server.pipeline().addLast(new ChannelPacketHandler(api, connection));
        }

        private List<Packet<?>> receiveWirePackets() {
            Packet<?> wirePacket;
            while ((wirePacket = server.readOutbound()) != null) {
                client.writeInbound(wirePacket);
            }
            List<Packet<?>> received = new ArrayList<>();
            Packet<?> packet;
            while ((packet = client.readInbound()) != null) {
                received.add(packet);
            }
            return received;
        }

        @Override
        public void close() {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }
}
