package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.PacketSendRoute;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.PacketStateData;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.LatencyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VelocityClientboundHandlingTest {
    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void vehicleProofUsesOneBundle(ProtocolVersion version) {
        checkVehicleProof(version, false);
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void vehicleProofStaysInsideTheServersBundle(ProtocolVersion version) {
        checkVehicleProof(version, true);
    }

    private void checkVehicleProof(ProtocolVersion version, boolean serverBundle) {
        try (var fixture = new Fixture(version)) {
            var proof = new CultPlayer.TrackedTransaction(1, new CultWrite(new ClientboundPing(123), false));
            when(fixture.player.supportsBundles()).thenReturn(true);
            // The player now owns appending and tracking a proof group. Exercise
            // that current contract, retaining the exact wire-order assertions below.
            doAnswer(call -> {
                        PacketSendEvent<?> event = call.getArgument(0);
                        List<CultWrite> beforeProof = call.getArgument(1);
                        event.getWritesAfterSend().addAll(beforeProof);
                        event.getWritesAfterSend().add(proof.packet());
                        event.getTasksAfterSend().add(() -> fixture.player.markTrackedTransactionPacketSent(proof));
                        return proof;
                    })
                    .when(fixture.player)
                    .appendTrailingProofTransaction(any(), anyList());
            var replication = new PacketEntityReplication(fixture.player);
            var callbackWriteCount = new AtomicInteger(-1);
            fixture.dispatcher.register(routes -> routes.send(
                    ClientboundPackets.SET_PASSENGERS,
                    PacketListenerPriority.NORMAL,
                    (event, player, packet) -> replication.appendVehicleProtocolProofTransaction(event)));
            // Send completion callbacks must observe the packet and its proof already queued.
            doAnswer(call -> {
                        callbackWriteCount.set(fixture.writtenNames.size());
                        return null;
                    })
                    .when(fixture.player)
                    .markTrackedTransactionPacketSent(proof);

            if (serverBundle) fixture.delimiter();
            // This is a backend packet the anticheat consumes, rather than one it encodes.
            ByteBuf passengers = Unpooled.buffer();
            Wire.writeVarInt(
                    passengers,
                    fixture.runtime
                            .data()
                            .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                            .id("minecraft:set_passengers"));
            Wire.writeVarInt(passengers, 42);
            Wire.writeVarInt(passengers, 1);
            Wire.writeVarInt(passengers, 7);
            assertTrue(fixture.channel.writeAndFlush(passengers).isSuccess());
            if (serverBundle) fixture.delimiter();

            assertEquals(
                    List.of("bundle_delimiter", "set_passengers", "ping", "bundle_delimiter"), fixture.wireNames());
            assertEquals(serverBundle ? 3 : 4, callbackWriteCount.get());
            verify(fixture.player).markTrackedTransactionPacketSent(proof);
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void wakeUpUsesTheObservedActionAndWaitsForItsProof(ProtocolVersion version) {
        int wakeUp = version.atLeast(ProtocolVersion.V26_3) ? 0 : 2;
        for (int action = 0; action <= 5; action++) {
            try (var fixture = new Fixture(version)) {
                fixture.player.entityID = 42;
                fixture.player.isInBed = true;
                fixture.player.latencyUtils = mock(LatencyUtils.class);
                var acknowledgement = new AtomicReference<Runnable>();
                doAnswer(call -> {
                            assertEquals(
                                    List.of("animate"), fixture.writtenNames, "The animation must precede its proof");
                            acknowledgement.set(call.getArgument(0));
                            return null;
                        })
                        .when(fixture.player.latencyUtils)
                        .addRealTimeTaskNext(any(Runnable.class));
                fixture.dispatcher.register(routes -> routes.send(
                        ClientboundPackets.ANIMATE,
                        PacketListenerPriority.NORMAL,
                        new PacketSelfMetadataListener()::onAnimate));
                ByteBuf frame = Unpooled.buffer();
                Wire.writeVarInt(
                        frame,
                        fixture.runtime
                                .data()
                                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                                .id("minecraft:animate"));
                Wire.writeVarInt(frame, 42);
                frame.writeByte(action);
                fixture.channel.writeAndFlush(frame);

                assertTrue(fixture.player.isInBed, "Receipt must be confirmed before releasing sleeping movement");
                if (action == wakeUp) {
                    assertNotNull(acknowledgement.get());
                    verify(fixture.player).sendTrailingTransaction();
                    acknowledgement.get().run();
                    assertFalse(fixture.player.isInBed);
                    verify(fixture.player).refreshPlayerPose();
                } else {
                    assertNull(acknowledgement.get(), "Swing and particle actions must not wake the player");
                    verify(fixture.player, never()).sendTrailingTransaction();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void foodUpdatesWaitForTheHealthPacketsOwnTransaction(ProtocolVersion version) throws Exception {
        for (boolean proofSendable : new boolean[] {true, false}) {
            for (int food : new int[] {6, 20}) {
                checkFoodUpdate(version, proofSendable, food);
            }
        }
    }

    private void checkFoodUpdate(ProtocolVersion version, boolean proofSendable, int food) throws Exception {
        try (var fixture = new Fixture(version)) {
            var sent = new AtomicInteger(2);
            var received = new AtomicInteger();
            var itemUse = mock(ClientItemUseState.class);
            for (var entry : Map.of(
                            "lastTransactionSent", sent,
                            "lastTransactionReceived", received,
                            "clientItemUse", itemUse)
                    .entrySet()) {
                var field = CultPlayer.class.getField(entry.getKey());
                field.setAccessible(true);
                field.set(fixture.player, entry.getValue());
            }
            fixture.player.food = food == 20 ? 6 : 20;
            int previousFood = fixture.player.food;
            fixture.player.packetStateData = new PacketStateData();
            fixture.player.compensatedEntities = mock(CompensatedEntities.class);
            when(fixture.player.compensatedEntities.getSelf()).thenReturn(mock(PacketEntitySelf.class));
            fixture.player.latencyUtils = new LatencyUtils(fixture.player);
            doAnswer(call -> {
                        int id = sent.incrementAndGet();
                        fixture.connection.write(new CultWrite(new ClientboundPing(id), false));
                        return null;
                    })
                    .when(fixture.player)
                    .sendTransaction();
            when(fixture.player.supportsBundles()).thenReturn(true);
            doCallRealMethod().when(fixture.player).runAtTrailingProof(any(), any());
            if (proofSendable) {
                doAnswer(call -> {
                            PacketSendEvent<?> event = call.getArgument(0);
                            var proof = new CultPlayer.TrackedTransaction(
                                    sent.incrementAndGet(), new CultWrite(new ClientboundPing(sent.get()), false));
                            event.getWritesAfterSend().add(proof.packet());
                            return proof;
                        })
                        .when(fixture.player)
                        .appendTrailingProofTransaction(any());
            }
            fixture.dispatcher.register(routes -> routes.send(
                    ClientboundPackets.SET_HEALTH,
                    PacketListenerPriority.NORMAL,
                    new PacketPlayerRespawn()::onSetHealth));
            ByteBuf frame = Unpooled.buffer();
            Wire.writeVarInt(
                    frame,
                    fixture.runtime
                            .data()
                            .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                            .id("minecraft:set_health"));
            frame.writeFloat(20);
            Wire.writeVarInt(frame, food);
            frame.writeFloat(5);
            fixture.channel.writeAndFlush(frame);
            assertEquals(
                    proofSendable
                            ? List.of("ping", "bundle_delimiter", "set_health", "ping", "bundle_delimiter")
                            : List.of("ping", "set_health", "ping"),
                    fixture.wireNames());
            assertEquals(4, sent.get());
            assertEquals(
                    previousFood, fixture.player.food, "Outstanding earlier pings do not confirm this health update");
            for (int id = 1; id <= 4; id++) {
                received.set(id);
                fixture.player.latencyUtils.handleNettySyncTransaction(id);
                // A rise lands at the preceding ping; the exact value lands at the following one.
                int expected = id < 3 ? previousFood : id < 4 ? Math.max(previousFood, food) : food;
                assertEquals(
                        expected,
                        fixture.player.food,
                        "Food changes at this packet's own pings, not an unrelated outstanding pong");
                verify(itemUse, times(id >= 4 ? 1 : 0)).foodReceived();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final CultPlayer player = mock(CultPlayer.class);
        final ProtocolRuntime runtime;
        final PacketDispatcher dispatcher;
        final EmbeddedChannel channel = new EmbeddedChannel();
        final CultConnection connection;
        final List<String> writtenNames = new ArrayList<>();

        Fixture(ProtocolVersion version) {
            runtime = ProtocolRuntime.create(ProtocolData.load(version));
            // Exercise real routes and transport without constructing unrelated movement state.
            dispatcher = new PacketDispatcher(runtime) {
                @Override
                public void send(PacketSendEvent<?> event, PacketSendRoute<Object> route) {
                    route.dispatch(event, player, event.getOriginalPacket());
                }
            };
            connection = new CultConnection(channel, dispatcher, ignored -> null);
            new User(new User.Profile(UUID.randomUUID(), "ProtocolTest"), connection);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY);
            channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    writtenNames.add(runtime.data()
                            .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                            .name(Wire.peekVarInt((ByteBuf) message))
                            .replace("minecraft:", ""));
                    context.write(message, promise);
                }
            });
            channel.pipeline().addLast(new CultEncoder(connection));
        }

        void delimiter() {
            connection.write(new CultWrite(ClientboundPackets.BUNDLE_DELIMITER.opaqueValue(), false));
        }

        List<String> wireNames() {
            var names = new ArrayList<String>();
            ByteBuf frame;
            while ((frame = channel.readOutbound()) != null) {
                try {
                    names.add(runtime.data()
                            .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                            .name(Wire.readVarInt(frame))
                            .replace("minecraft:", ""));
                } finally {
                    frame.release();
                }
            }
            return names;
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }
}
