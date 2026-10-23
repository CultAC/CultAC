package ac.cult.cultac.network;

import static ac.cult.cultac.protocol.ConnectionPhase.*;
import static ac.cult.cultac.protocol.PacketDirection.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.logging.PacketLogObserver;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Replacement contract, written before the transport implementation (D1–D7, C1/C2). */
class CultTransportTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));

    // The decoder reads CultAPI's config; it must initialize before a test binds a mock host.
    @BeforeAll
    static void initializeApi() {
        assertNotNull(CultAPI.INSTANCE.getConfigManager());
    }

    @Test
    void engineRequestsHandoffAfterAckListenersAndBeforeNativeDecoder() throws Exception {
        var context = ac.cult.cultac.runtime.RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        Object previous = context.get(null);
        try (var f = new Fixture(false)) {
            var order = new ArrayList<String>();
            var host = mock(ac.cult.reload.api.HostContext.class);
            when(host.platform()).thenReturn("velocity");
            when(host.handoff(f.channel, null)).thenAnswer(invocation -> {
                f.assertOwner();
                order.add("handoff");
                return true;
            });
            f.routes.receive(ServerboundPackets.CONFIGURATION_ACKNOWLEDGED, event -> order.add("listeners"));
            f.connection.phase(CLIENTBOUND, CONFIGURATION);
            f.channel.pipeline().addAfter("decoder", "native-ack", new ChannelInboundHandlerAdapter() {
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    // The terminal is still encoded in PLAY when the native decoder consumes it.
                    assertEquals(PLAY, f.connection.phase(SERVERBOUND));
                    order.add("native");
                    ctx.fireChannelRead(message);
                }
            });
            context.set(null, host);
            f.channel.writeInbound(f.frame(SERVERBOUND, PLAY, "configuration_acknowledged"));
            ((ByteBuf) f.channel.readInbound()).release();
            assertEquals(List.of("listeners", "handoff", "native"), order);
            assertEquals(CONFIGURATION, f.connection.phase(SERVERBOUND));
        } finally {
            context.set(null, previous);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true"})
    void aFailedHandoffClosesTheConnectionOnlyWhenNotFailingOpen(boolean observeOnly, boolean failOpen)
            throws Exception {
        var context = ac.cult.cultac.runtime.RuntimeEnvironment.class.getDeclaredField("context");
        context.setAccessible(true);
        Object previous = context.get(null);
        try (var f = new Fixture(false);
                var services = new RecordConsumerServices();
                var ignored = RecordReceiveFixture.observeOnly(observeOnly);
                var launchSafety = RecordReceiveFixture.failOpen(failOpen)) {
            var host = mock(ac.cult.reload.api.HostContext.class);
            when(host.platform()).thenReturn("velocity");
            when(host.handoff(f.channel, null)).thenThrow(new IllegalStateException("adopt failed"));
            f.connection.phase(CLIENTBOUND, CONFIGURATION);
            context.set(null, host);
            var ack = f.frame(SERVERBOUND, PLAY, "configuration_acknowledged");
            String bytes = ByteBufUtil.hexDump(ack);
            f.channel.writeInbound(ack);
            ByteBuf forwarded = f.channel.readInbound();
            if (observeOnly || failOpen) {
                assertEquals(bytes, ByteBufUtil.hexDump(forwarded));
                forwarded.release();
                assertTrue(f.channel.isOpen());
            } else {
                assertNull(forwarded);
                assertFalse(f.channel.isOpen());
            }
        } finally {
            context.set(null, previous);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bedrockHandlersUseTheAssignedExecutorAndReentryKeepsItsQueuePosition(boolean encoderFirst) throws Exception {
        var owner = new DefaultEventExecutor(new io.netty.util.concurrent.DefaultThreadFactory("test-geyser-owner"));
        var channel = new EmbeddedChannel();
        var routes = new TestTransportRoutes(RUNTIME);
        var connection =
                new CultConnection(channel, routes.dispatcher, ignored -> new PacketOwner(owner, new Object()));
        connection.phase(SERVERBOUND, CONFIGURATION);
        connection.phase(CLIENTBOUND, PLAY);
        new User(new User.Profile(UUID.randomUUID(), "OwnerTest"), connection);
        if (encoderFirst) channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        channel.pipeline().addLast("decoder", new ChannelInboundHandlerAdapter());
        if (!encoderFirst) channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        CultDecoder.install(connection);
        CultEncoder.install(connection);
        var seen = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        routes.receive(ServerboundPackets.PONG, event -> {
            assertTrue(owner.inEventLoop());
            seen.add(event.getPacket().id());
        });
        routes.send(ClientboundPackets.PING, event -> {
            assertTrue(owner.inEventLoop());
            seen.add(event.getPacket().id());
            if (event.getPacket().id() == 3) connection.write(new CultWrite(new ClientboundPing(4), false));
        });
        try {
            for (int id : List.of(1, 2)) {
                var input = Unpooled.buffer();
                Wire.writeVarInt(
                        input,
                        RUNTIME.data().packets(CONFIGURATION, SERVERBOUND).id("minecraft:pong"));
                input.writeInt(id);
                channel.writeInbound(input);
            }
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            channel.runPendingTasks();
            assertSame(owner, channel.pipeline().context(CultDecoder.NAME).executor());
            assertSame(owner, channel.pipeline().context(CultEncoder.NAME).executor());
            var output = Unpooled.buffer();
            Wire.writeVarInt(output, RUNTIME.data().packets(PLAY, CLIENTBOUND).id("minecraft:ping"));
            output.writeInt(3);
            channel.writeAndFlush(output);
            owner.submit(() -> {}).get(5, TimeUnit.SECONDS);
            channel.runPendingTasks();
            // A recursive write of the active family is emitted once without redispatching that family.
            assertEquals(List.of(1, 2, 3), seen);
            for (int id : List.of(1, 2)) {
                var input = (ByteBuf) channel.readInbound();
                assertNotNull(input);
                Wire.readVarInt(input);
                assertEquals(id, input.readInt());
                input.release();
            }
            for (int id : List.of(4, 3)) {
                var input = (ByteBuf) channel.readOutbound();
                assertNotNull(input);
                Wire.readVarInt(input);
                assertEquals(id, input.readInt());
                input.release();
            }
            assertNull(channel.readInbound());
            assertNull(channel.readOutbound());
            assertTrue(channel.config().isAutoRead());
        } finally {
            channel.finishAndReleaseAll();
            owner.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
            channel.runPendingTasks();
        }
    }

    @Test
    void unlistenedFramesKeepTheirIdentityIndicesAndReferenceCountsWithoutBufferAllocation() throws Exception {
        try (var f = new Fixture(false)) {
            var allocations = new AtomicInteger();
            f.channel.config().setAllocator(new AbstractByteBufAllocator(false) {
                @Override
                protected ByteBuf newHeapBuffer(int initial, int maximum) {
                    allocations.incrementAndGet();
                    return new UnpooledHeapByteBuf(this, initial, maximum);
                }

                @Override
                protected ByteBuf newDirectBuffer(int initial, int maximum) {
                    allocations.incrementAndGet();
                    return new UnpooledDirectByteBuf(this, initial, maximum);
                }

                @Override
                public boolean isDirectBufferPooled() {
                    return false;
                }
            });
            ByteBuf inbound = f.pong(PLAY, 7), outbound = f.ping(PLAY, 8);
            int inIndex = inbound.readerIndex(), outIndex = outbound.readerIndex();
            assertTrue(f.channel.writeInbound(inbound));
            assertSame(inbound, f.channel.readInbound());
            assertTrue(f.channel.writeOutbound(outbound));
            assertSame(outbound, f.channel.readOutbound());
            assertEquals(inIndex, inbound.readerIndex());
            assertEquals(outIndex, outbound.readerIndex());
            assertEquals(1, inbound.refCnt());
            assertEquals(1, outbound.refCnt());
            assertEquals(0, allocations.get());
            assertTrue(f.channel.config().isAutoRead());
            inbound.release();
            outbound.release();
        }
    }

    @Test
    void anEmptyInboundFrameIsForwardedUnread() {
        for (boolean owned : List.of(false, true)) {
            try (var f = new Fixture(owned)) {
                var observed = new AtomicInteger();
                f.connection.packetObserver(new PacketLogObserver() {
                    @Override
                    public void packet(PacketDirection direction, ConnectionPhase phase, ByteBuf frame) {
                        observed.incrementAndGet();
                    }
                });
                f.routes.receive(ServerboundPackets.PONG, event -> {});
                // Vanilla's ByteToMessageDecoder never decodes an empty buffer.
                f.channel.pipeline().replace("decoder", "decoder", new ChannelInboundHandlerAdapter());
                ByteBuf empty = Unpooled.buffer(0);
                f.channel.writeInbound(empty);
                f.pump();
                assertSame(empty, f.channel.readInbound());
                assertEquals(1, empty.refCnt());
                assertTrue(f.channel.isOpen());
                assertEquals(0, observed.get(), "the empty frame is not read");
                empty.release();
            }
        }
    }

    @Test
    void routesPublishedBetweenFramesReachTheNextFrameInBothDirections() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                var received = new ArrayList<String>();
                var sent = new ArrayList<String>();
                f.routes.receive(
                        ServerboundPackets.PONG,
                        event -> received.add("a" + event.getPacket().id()));
                f.routes.send(
                        ClientboundPackets.PING,
                        event -> sent.add("a" + event.getPacket().id()));
                f.channel.writeInbound(f.pong(PLAY, 1));
                f.channel.writeAndFlush(f.ping(PLAY, 1));
                f.pump();
                f.routes.receive(ServerboundPackets.PONG, event -> {
                    received.add("b" + event.getPacket().id());
                    f.routes.receive(
                            ServerboundPackets.PONG,
                            next -> received.add("c" + next.getPacket().id()));
                });
                f.routes.send(ClientboundPackets.PING, event -> {
                    sent.add("b" + event.getPacket().id());
                    f.routes.send(
                            ClientboundPackets.PING,
                            next -> sent.add("c" + next.getPacket().id()));
                });
                f.channel.writeInbound(f.pong(PLAY, 2), f.pong(PLAY, 3));
                f.channel.writeAndFlush(f.ping(PLAY, 2));
                f.channel.writeAndFlush(f.ping(PLAY, 3));
                f.pump();
                assertEquals(List.of("a1", "b2", "c3"), received);
                assertEquals(List.of("a1", "b2", "c3"), sent);
                for (int id = 1; id <= 3; id++) assertEquals(f.hex(f.pong(PLAY, id)), f.hex(f.channel.readInbound()));
                assertNull(f.channel.readInbound());
                assertEquals(List.of("P1", "P2", "P3"), f.output());
            }
    }

    @Test
    void receiveAndSendCancellationAndReplacementWorkSynchronously() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                f.routes.receive(ServerboundPackets.PONG, event -> {
                    f.assertOwner();
                    if (event.getPacket().id() == 1) event.setCancelled(true);
                    else event.replace(new ac.cult.cultac.protocol.packet.serverbound.ServerboundPong(22));
                });
                f.routes.send(ClientboundPackets.PING, event -> {
                    f.assertOwner();
                    if (event.getPacket().id() == 1) event.setCancelled(true);
                    else event.replace(new ClientboundPing(33));
                });
                ByteBuf cancelled = f.pong(PLAY, 1), replaced = f.pong(PLAY, 2);
                f.channel.writeInbound(cancelled, replaced);
                f.pump();
                assertEquals(0, cancelled.refCnt());
                assertEquals(0, replaced.refCnt());
                ByteBuf accepted = f.channel.readInbound();
                assertEquals(f.hex(f.pong(PLAY, 22)), f.hex(accepted));
                assertNull(f.channel.readInbound());
                var promise = f.channel.newPromise();
                f.channel.writeAndFlush(f.ping(PLAY, 1), promise);
                f.channel.writeAndFlush(f.ping(PLAY, 2));
                f.pump();
                assertTrue(promise.isSuccess());
                assertEquals(List.of("P33"), f.output());
            }
    }

    @Test
    void beforeAfterAndRequestedSingleFrameBundlesRespectTheWireBundle() throws Exception {
        for (boolean owned : List.of(false, true))
            for (boolean inside : List.of(false, true)) {
                try (var f = new Fixture(owned)) {
                    f.routes.send(ClientboundPackets.PING, event -> {
                        if (event.getPacket().id() != 2) return;
                        assertEquals(inside, event.isInsideBundle());
                        event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(1), false));
                        event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(3), false));
                    });
                    if (inside) f.channel.writeOutbound(f.delimiter());
                    f.channel.writeOutbound(f.ping(PLAY, 2));
                    if (inside) f.channel.writeOutbound(f.delimiter());
                    f.pump();
                    assertEquals(List.of("D", "P1", "P2", "P3", "D"), f.output());
                }
            }
        try (var f = new Fixture(false)) {
            f.routes.send(ClientboundPackets.PING, event -> event.bundle());
            f.channel.writeOutbound(f.ping(PLAY, 9));
            assertEquals(List.of("D", "P9", "D"), f.output());
        }
    }

    @Test
    void childrenDispatchDepthFirstWithTheCycleRuleAndEachProofOnce() throws Exception {
        try (var f = new Fixture(true)) {
            var trace = new ArrayList<String>();
            f.routes.send(ClientboundPackets.SET_PASSENGERS, event -> {
                trace.add("mount");
                event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(11), false));
                event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(12), false));
            });
            f.routes.send(ClientboundPackets.PING, event -> {
                trace.add("ping:" + event.getPacket().id());
                event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(10), false));
            });
            ByteBuf mount = f.frame(CLIENTBOUND, PLAY, "set_passengers");
            Wire.writeVarInt(mount, 7);
            Wire.writeVarInt(mount, 0);
            f.channel.writeOutbound(mount);
            f.pump();
            assertEquals(List.of("mount", "ping:11", "ping:12"), trace);
            assertEquals(List.of("D", "minecraft:set_passengers", "P10", "P11", "P10", "P12", "D"), f.output());
        }
    }

    @Test
    void reentrantUserWritesPrecedeTheOpeningGroupDelimiter() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                var pre = new ArrayList<java.util.concurrent.CompletionStage<Void>>();
                f.routes.send(ClientboundPackets.PING, event -> {
                    if (event.getPacket().id() != 2) return;
                    pre.add(f.user.write(new ClientboundPing(0)));
                    event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(1), false));
                    event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(3), false));
                });
                f.channel.writeOutbound(f.ping(PLAY, 2));
                f.pump();
                assertEquals(List.of("P0", "D", "P1", "P2", "P3", "D"), f.output());
                assertTrue(pre.getFirst().toCompletableFuture().isDone());
                assertFalse(pre.getFirst().toCompletableFuture().isCompletedExceptionally());
            }
    }

    @Test
    void reentrantWritesObserveTheirPositionOutsideAnAuthoredBundle() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                var membership = new ArrayList<Boolean>();
                f.routes.send(ClientboundPackets.ENTITY_MOTION, event -> {
                    assertTrue(event.isInsideBundle());
                    f.user.write(new ClientboundPing(0));
                });
                f.routes.send(ClientboundPackets.PING, event -> membership.add(event.isInsideBundle()));
                var promise = f.connection.write(
                        List.of(new CultWrite(
                                new ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion(
                                        7, new ac.cult.cultac.protocol.value.Vec3d(0, 0, 0)),
                                false)),
                        true);
                f.pump();
                assertTrue(promise.isSuccess(), () -> "done=" + promise.isDone() + " cause=" + promise.cause());
                assertEquals(List.of(false), membership);
                assertEquals(List.of("P0", "D", "minecraft:set_entity_motion", "D"), f.output());
            }
    }

    @Test
    void silentWritesAndUnavailablePhaseWritesHaveCorrectPromises() throws Exception {
        try (var f = new Fixture(true)) {
            var calls = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> calls.incrementAndGet());
            var silent = f.connection.write(new CultWrite(new ClientboundPing(7), true));
            f.pump();
            assertTrue(silent.isSuccess());
            assertEquals(0, calls.get());
            assertEquals(List.of("P7"), f.output());
            f.connection.phase(CLIENTBOUND, LOGIN);
            var rejected = f.connection.write(new CultWrite(new ClientboundPing(8), false));
            f.pump();
            assertTrue(rejected.isDone());
            assertFalse(rejected.isSuccess());
            assertInstanceOf(UnsupportedOnVersionException.class, rejected.cause());
            assertTrue(f.output().isEmpty());
            assertTrue(f.channel.isOpen());
        }
    }

    @Test
    void nestedReentrantGroupsFinishBeforeTheirParentsWithoutNestedDelimiters() throws Exception {
        for (boolean owned : List.of(false, true))
            for (boolean bundled : List.of(false, true)) {
                for (boolean inside : List.of(false, true))
                    try (var f = new Fixture(owned)) {
                        var completions = new ArrayList<java.util.concurrent.CompletionStage<Void>>();
                        var calls = new ArrayList<String>();
                        f.routes.send(ClientboundPackets.SET_PASSENGERS, event -> {
                            calls.add("mount");
                            completions.add(f.user.write(
                                    List.of(
                                            new CultWrite(
                                                    new ac.cult.cultac.protocol.packet.clientbound
                                                            .ClientboundEntityMotion(
                                                            7, new ac.cult.cultac.protocol.value.Vec3d(0, 0, 0)),
                                                    false),
                                            new CultWrite(new ClientboundPing(5), false)),
                                    bundled));
                            event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(1), false));
                            event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(3), false));
                        });
                        f.routes.send(ClientboundPackets.ENTITY_MOTION, event -> {
                            calls.add("motion");
                            assertEquals(inside || bundled, event.isInsideBundle());
                            completions.add(f.user.write(new ClientboundPing(0)));
                        });
                        f.routes.send(ClientboundPackets.PING, event -> {
                            int id = event.getPacket().id();
                            calls.add("ping:" + id);
                            if (id == 0) {
                                assertEquals(inside, event.isInsideBundle());
                                event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(-1), false));
                            }
                        });
                        ByteBuf mount = f.frame(CLIENTBOUND, PLAY, "set_passengers");
                        Wire.writeVarInt(mount, 7);
                        Wire.writeVarInt(mount, 0);
                        if (inside) f.channel.writeOutbound(f.delimiter());
                        f.channel.writeOutbound(mount);
                        if (inside) f.channel.writeOutbound(f.delimiter());
                        f.pump();

                        var expected = new ArrayList<String>();
                        expected.addAll(List.of("D", "P-1", "P0"));
                        if (!inside) expected.add("D");
                        if (bundled && !inside) expected.add("D");
                        expected.addAll(List.of("minecraft:set_entity_motion", "P5"));
                        if (bundled && !inside) expected.add("D");
                        if (!inside) expected.add("D");
                        expected.addAll(List.of("P1", "minecraft:set_passengers", "P3", "D"));
                        assertEquals(expected, f.output());
                        assertEquals(List.of("mount", "motion", "ping:0", "ping:5", "ping:1", "ping:3"), calls);
                        for (var completion : completions) {
                            assertDoesNotThrow(
                                    () -> completion.toCompletableFuture().get(5, TimeUnit.SECONDS));
                        }
                    }
            }
    }

    @Test
    void cancellingTheParentKeepsCompletedReentrantWritesButDiscardsItsInjectionsAndTasks() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                f.routes.send(ClientboundPackets.PING, event -> {
                    f.user.write(new ClientboundPing(0));
                    event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(3), false));
                    event.getTasksAfterSend().add(() -> fail("Cancelled task ran"));
                    event.setCancelled(true);
                });
                var promise = f.channel.writeAndFlush(f.ping(PLAY, 2));
                f.pump();
                assertEquals(List.of("P0"), f.output());
                assertTrue(promise.isSuccess());
            }
    }

    @Test
    void emptyAuthoredGroupsAndUnavailableChildrenSettleOnBothOwners() throws Exception {
        for (boolean owned : List.of(false, true))
            try (var f = new Fixture(owned)) {
                var empty = f.connection.write(List.of(), true);
                f.pump();
                assertTrue(empty.isSuccess());
                assertEquals(List.of("D", "D"), f.output());
                f.connection.phase(CLIENTBOUND, CONFIGURATION);
                var partial = f.connection.write(
                        List.of(
                                new CultWrite(new ClientboundPing(1), false),
                                new CultWrite(
                                        new ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion(
                                                7, new ac.cult.cultac.protocol.value.Vec3d(0, 0, 0)),
                                        false)),
                        false);
                f.pump();
                assertTrue(partial.isDone());
                assertInstanceOf(UnsupportedOnVersionException.class, partial.cause());
                ByteBuf frame = f.channel.readOutbound();
                try {
                    assertEquals(f.hex(f.ping(CONFIGURATION, 1)), ByteBufUtil.hexDump(frame));
                } finally {
                    frame.release();
                }
                assertNull(f.channel.readOutbound());
                assertTrue(f.channel.isOpen());
            }
    }

    @Test
    void inboundProcessingFinishesInlineWithoutChangingAutoRead() throws Exception {
        try (var f = new Fixture(false)) {
            var seen = new ArrayList<Integer>();
            f.routes.receive(ServerboundPackets.PONG, event -> {
                f.assertOwner();
                seen.add(event.getPacket().id());
            });
            var frame = f.pong(PLAY, 7);
            f.channel.writeInbound(frame);
            assertEquals(List.of(7), seen);
            assertSame(frame, f.channel.readInbound());
            frame.release();
            assertTrue(f.channel.config().isAutoRead());
        }
    }

    @Test
    void outboundProcessingSettlesItsPromiseInline() throws Exception {
        try (var f = new Fixture(false)) {
            f.routes.send(ClientboundPackets.PING, event -> f.assertOwner());
            var promise = f.channel.writeAndFlush(f.ping(PLAY, 7));
            assertTrue(promise.isSuccess());
            assertEquals(List.of("P7"), f.output());
        }
    }

    @Test
    void originalPromiseBelongsToTheOriginalFrameAndTasksRunAfterEmission() throws Exception {
        try (var f = new Fixture(true)) {
            var caller = f.channel.newPromise();
            var emitted = new ArrayList<String>();
            var tasks = new ArrayList<String>();
            f.channel.pipeline().addFirst("wire-observer", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    f.assertOwner();
                    ByteBuf frame = (ByteBuf) message;
                    String name = f.name(frame);
                    if (name.equals("P2")) assertSame(caller, promise);
                    else assertNotSame(caller, promise);
                    emitted.add(name);
                    ctx.write(message, promise);
                }
            });
            f.routes.send(ClientboundPackets.PING, event -> {
                if (event.getPacket().id() != 2) return;
                event.getWritesAfterSend().add(new CultWrite(new ClientboundPing(3), false));
                event.getTasksAfterSend().add(() -> {
                    f.assertOwner();
                    assertEquals(List.of("D", "P2", "P3", "D"), emitted);
                    tasks.add("after");
                });
            });
            f.channel.writeAndFlush(f.ping(PLAY, 2), caller);
            f.pump();
            assertTrue(caller.isSuccess());
            assertEquals(List.of("after"), tasks);
        }
    }

    @Test
    void emptyWireBundlesAreEmittedVerbatim() throws Exception {
        try (var f = new Fixture(true)) {
            f.routes.send(ClientboundPackets.PING, event -> event.setCancelled(true));
            f.channel.writeOutbound(f.delimiter(), f.ping(PLAY, 1), f.delimiter());
            f.pump();
            assertEquals(List.of("D", "D"), f.output());
        }
    }

    @Test
    void everyProtocolSwitchUsesItsDirectionalWirePhaseAndRejectsEdits() throws Exception {
        try (var f = new Fixture(false)) {
            for (var type : ConnectionLifecycle.types()) {
                if (type == ServerboundPackets.INTENTION) continue;
                ConnectionPhase initial = type.phases().iterator().next();
                f.connection.phase(type.direction(), initial);
                if (type.direction() == SERVERBOUND)
                    f.routes.receive((PacketType) type, event -> {
                        assertEquals(initial, event.getPhase());
                        assertThrows(IllegalStateException.class, () -> event.setCancelled(true));
                        assertThrows(IllegalStateException.class, () -> event.replace(event.getPacket()));
                    });
                else
                    f.routes.send((PacketType) type, event -> {
                        assertEquals(initial, event.getPhase());
                        assertThrows(IllegalStateException.class, () -> event.setCancelled(true));
                        assertThrows(IllegalStateException.class, () -> event.replace(event.getPacket()));
                    });
                String wireName =
                        type.wireNames(ProtocolData.load(ProtocolVersion.V26_3)).getFirst();
                ByteBuf terminal = f.frame(type.direction(), initial, wireName);
                if (type == ClientboundPackets.LOGIN_FINISHED)
                    terminal.writeLong(0).writeLong(0).writeByte(0).writeByte(0);
                if (type.direction() == SERVERBOUND) {
                    f.configureInbound();
                    f.channel.writeInbound(terminal);
                    ((ByteBuf) f.channel.readInbound()).release();
                } else f.channel.writeOutbound(terminal);
                ConnectionPhase next = type == ClientboundPackets.FINISH_CONFIGURATION
                                || type == ServerboundPackets.FINISH_CONFIGURATION
                        ? PLAY
                        : CONFIGURATION;
                assertEquals(next, f.connection.phase(type.direction()));
            }
            f.connection.phase(SERVERBOUND, HANDSHAKE);
            f.connection.phase(CLIENTBOUND, HANDSHAKE);
            f.configureInbound();
            f.routes.receive(ServerboundPackets.INTENTION, event -> {
                assertThrows(IllegalStateException.class, () -> event.setCancelled(true));
                assertThrows(IllegalStateException.class, () -> event.replace(event.getPacket()));
            });
            ByteBuf intention = f.frame(SERVERBOUND, HANDSHAKE, "intention");
            Wire.writeVarInt(intention, 777);
            Wire.writeString(intention, "localhost", 255);
            intention.writeShort(25565);
            Wire.writeVarInt(intention, 2);
            f.channel.writeInbound(intention);
            ((ByteBuf) f.channel.readInbound()).release();
            assertEquals(LOGIN, f.connection.phase(SERVERBOUND));
            assertEquals(LOGIN, f.connection.phase(CLIENTBOUND));
        }
    }

    @Test
    void compressionEventsPreserveCultAndPluginHandlers() throws Exception {
        try (var f = new Fixture(false)) {
            ChannelHandler decoder = f.channel.pipeline().get("decoder"),
                    encoder = f.channel.pipeline().get("encoder");
            var plugin = new ChannelDuplexHandler();
            f.channel.pipeline().addBefore("decoder", "plugin", plugin);
            ChannelHandler cultDecoder = f.channel.pipeline().get("cult-decoder"),
                    cultEncoder = f.channel.pipeline().get("cult-encoder");
            // Host compression events pass through the shared codecs unchanged.
            Object event = new Object();
            f.channel.pipeline().fireUserEventTriggered(event);
            assertSame(decoder, f.channel.pipeline().get("decoder"));
            assertSame(encoder, f.channel.pipeline().get("encoder"));
            assertSame(plugin, f.channel.pipeline().get("plugin"));
            assertSame(cultDecoder, f.channel.pipeline().get("cult-decoder"));
            assertSame(cultEncoder, f.channel.pipeline().get("cult-encoder"));
            assertEquals(
                    List.of("flow", "cult-decoder", "plugin", "decoder", "cult-encoder", "encoder"),
                    f.channel.pipeline().names().stream()
                            .filter(n -> !n.startsWith("DefaultChannelPipeline$"))
                            .toList());
            f.channel.pipeline().fireUserEventTriggered(event);
            assertSame(cultDecoder, f.channel.pipeline().get("cult-decoder"));
            assertSame(cultEncoder, f.channel.pipeline().get("cult-encoder"));
            assertSame(
                    f.channel.eventLoop(),
                    f.channel.pipeline().context("cult-decoder").executor());
            assertSame(
                    f.channel.eventLoop(),
                    f.channel.pipeline().context("cult-encoder").executor());
        }
    }

    @Test
    void upstreamFlowControlHoldsFramesAcrossProtocolChanges() throws Exception {
        try (var f = new Fixture(true, false)) {
            f.connection.phase(SERVERBOUND, LOGIN);
            f.channel.writeInbound(f.frame(SERVERBOUND, LOGIN, "login_acknowledged"));
            ((ByteBuf) f.channel.readInbound()).release();
            assertEquals(0, f.ownerLookups.get());
            ByteBuf config = f.pong(CONFIGURATION, 1),
                    finish = f.frame(SERVERBOUND, CONFIGURATION, "finish_configuration"),
                    play = f.pong(PLAY, 2);
            f.routes.receive(ServerboundPackets.PONG, event -> f.assertOwner());
            f.channel.writeInbound(config, finish, play);
            f.configureInbound();
            f.pump();
            assertSame(config, f.channel.readInbound());
            config.release();
            assertSame(finish, f.channel.readInbound());
            finish.release();
            assertEquals(PLAY, f.connection.phase(SERVERBOUND));
            assertNull(f.channel.readInbound());
            assertFalse(f.channel.config().isAutoRead());
            f.configureInbound();
            f.pump();
            assertSame(play, f.channel.readInbound());
            play.release();
            ByteBuf ack = f.frame(SERVERBOUND, PLAY, "configuration_acknowledged"),
                    configAgain = f.pong(CONFIGURATION, 3),
                    finishAgain = f.frame(SERVERBOUND, CONFIGURATION, "finish_configuration"),
                    playAgain = f.pong(PLAY, 4);
            f.channel.writeInbound(ack, configAgain, finishAgain, playAgain);
            f.pump();
            assertSame(ack, f.channel.readInbound());
            ack.release();
            assertEquals(CONFIGURATION, f.connection.phase(SERVERBOUND));
            assertNull(f.channel.readInbound());
            f.configureInbound();
            f.pump();
            assertSame(configAgain, f.channel.readInbound());
            configAgain.release();
            assertSame(finishAgain, f.channel.readInbound());
            finishAgain.release();
            assertEquals(PLAY, f.connection.phase(SERVERBOUND));
            assertNull(f.channel.readInbound());
            f.configureInbound();
            f.pump();
            assertSame(playAgain, f.channel.readInbound());
            playAgain.release();
            assertEquals(1, f.ownerLookups.get());
        }
    }

    @Test
    void synchronousProxyConfigurationResumesQueuedPlayFramesWithoutHostReadRequest() throws Exception {
        try (var f = new Fixture(true)) {
            f.channel.pipeline().replace(CultDecoder.NAME, CultDecoder.NAME, new CultDecoder(f.connection));
            var observed = new ArrayList<String>();
            f.channel.pipeline().replace("decoder", "decoder", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf view = ((ByteBuf) message).duplicate();
                    var phase = f.connection.phase(SERVERBOUND);
                    observed.add(phase + ":"
                            + RUNTIME.data().packets(phase, SERVERBOUND).name(Wire.readVarInt(view)));
                    ctx.fireChannelRead(message);
                    // Velocity switches its session handler here, without requesting a read.
                }
            });
            f.connection.phase(SERVERBOUND, CONFIGURATION);
            var frames = List.of(
                    f.frame(SERVERBOUND, CONFIGURATION, "finish_configuration"),
                    f.pong(PLAY, 1),
                    f.frame(SERVERBOUND, PLAY, "configuration_acknowledged"),
                    f.pong(CONFIGURATION, 2),
                    f.frame(SERVERBOUND, CONFIGURATION, "finish_configuration"),
                    f.pong(PLAY, 3));
            f.channel.writeInbound(frames.toArray());
            f.pump();
            f.pump();
            for (ByteBuf frame : frames) {
                assertSame(frame, f.channel.readInbound());
                frame.release();
            }
            assertEquals(
                    List.of(
                            "CONFIGURATION:minecraft:finish_configuration",
                            "PLAY:minecraft:pong",
                            "PLAY:minecraft:configuration_acknowledged",
                            "CONFIGURATION:minecraft:pong",
                            "CONFIGURATION:minecraft:finish_configuration",
                            "PLAY:minecraft:pong"),
                    observed);
            assertEquals(PLAY, f.connection.phase(SERVERBOUND));
        }
    }

    @Test
    void upstreamFlowControlOwnsHostReadPauseAndCultLeavesItUnchanged() throws Exception {
        try (var f = new Fixture(false)) {
            var first = f.pong(PLAY, 1);
            var second = f.pong(PLAY, 2);
            f.channel.config().setAutoRead(false);
            f.channel.writeInbound(first, second);
            assertNull(f.channel.readInbound());
            f.channel.read();
            assertSame(first, f.channel.readInbound());
            first.release();
            assertFalse(f.channel.config().isAutoRead());
            assertNull(f.channel.readInbound());
            f.channel.read();
            assertSame(second, f.channel.readInbound());
            second.release();
            assertFalse(f.channel.config().isAutoRead());
        }
    }

    @Test
    void nativeConfigurationTasksAndPacketWritesPassSynchronously() throws Exception {
        try (var f = new Fixture(false)) {
            f.channel.pipeline().replace("decoder", "inbound_config", new ConfigurationInbound());
            assertTrue(f.channel
                    .writeAndFlush(new CultWrite(new ClientboundPing(8), false))
                    .isSuccess());
            ConfigurationTask task = ctx -> {
                assertSame(f.channel.eventLoop(), ctx.executor());
                ctx.pipeline().replace(ctx.name(), "decoder", new ChannelInboundHandlerAdapter());
                ctx.channel().config().setAutoRead(true);
            };
            assertTrue(f.channel.writeAndFlush(task).isSuccess());
            assertEquals(List.of("P8"), f.output());
        }
    }

    @Test
    void successiveInboundFramesKeepWireOrderOnTheChannelLoop() throws Exception {
        try (var f = new Fixture(false)) {
            var seen = new ArrayList<Integer>();
            f.routes.receive(
                    ServerboundPackets.PONG, event -> seen.add(event.getPacket().id()));
            var one = f.pong(PLAY, 1);
            var two = f.pong(PLAY, 2);
            f.channel.writeInbound(one, two);
            assertEquals(List.of(1, 2), seen);
            assertSame(one, f.channel.readInbound());
            assertSame(two, f.channel.readInbound());
            one.release();
            two.release();
        }
    }

    @Test
    void setbackOverridesCancellationOrReplacementOnlyForTheNextPositionMove() throws Exception {
        for (boolean owned : List.of(false, true))
            for (boolean cancel : List.of(false, true)) {
                try (var f = new Fixture(owned)) {
                    var player = mock(CultPlayer.class);
                    var setbacks = mock(SetbackTeleportUtil.class);
                    when(player.getSetbackTeleportUtil()).thenReturn(setbacks);
                    when(setbacks.takePendingServerMove())
                            .thenReturn(new ServerboundMovePlayer(40, 50, 60, 0, 0, false, false, true, false));
                    var userField = CultPlayer.class.getDeclaredField("user");
                    userField.setAccessible(true);
                    userField.set(player, f.user);
                    TestUsers.attach(f.user, player);
                    f.routes.receive(ServerboundPackets.MOVE_PLAYER, event -> {
                        if (!event.getPacket().hasPosition()) return;
                        if (cancel) event.setCancelled(true);
                        else
                            event.replace(new ServerboundMovePlayer(100, 100, 100, 100, 100, false, false, true, true));
                    });
                    ByteBuf rotation = f.frame(SERVERBOUND, PLAY, "move_player_rot")
                            .writeFloat(7)
                            .writeFloat(8)
                            .writeByte(1);
                    ByteBuf status = f.frame(SERVERBOUND, PLAY, "move_player_status_only")
                            .writeByte(1);
                    ByteBuf position = f.frame(SERVERBOUND, PLAY, "move_player_pos_rot")
                            .writeDouble(1)
                            .writeDouble(2)
                            .writeDouble(3)
                            .writeFloat(7)
                            .writeFloat(8)
                            .writeByte(1);
                    f.channel.writeInbound(rotation, status, position);
                    f.pump();
                    assertSame(rotation, f.channel.readInbound());
                    rotation.release();
                    assertSame(status, f.channel.readInbound());
                    status.release();
                    ByteBuf actual = f.channel.readInbound();
                    ByteBuf expected = f.frame(SERVERBOUND, PLAY, "move_player_pos_rot")
                            .writeDouble(40)
                            .writeDouble(50)
                            .writeDouble(60)
                            .writeFloat(7)
                            .writeFloat(8)
                            .writeByte(0);
                    assertEquals(f.hex(expected), f.hex(actual));
                    assertEquals(0, position.refCnt());
                    assertNull(f.channel.readInbound());
                    verify(setbacks, times(1)).takePendingServerMove();
                }
            }
    }

    @Test
    void malformedRoutedFramesStayWithTheHostAndLaterPacketsAreStillInspected() throws Exception {
        try (var f = new Fixture(true)) {
            var received = new ArrayList<Integer>();
            f.routes.receive(
                    ServerboundPackets.PONG,
                    event -> received.add(event.getPacket().id()));
            var decoder = f.channel.pipeline().get(CultDecoder.NAME);
            var encoder = f.channel.pipeline().get(CultEncoder.NAME);
            ByteBuf frame = f.frame(SERVERBOUND, PLAY, "pong");
            f.channel.writeInbound(frame);
            f.pump();
            assertSame(frame, f.channel.readInbound());
            frame.release();
            assertEquals(0, frame.refCnt());
            assertTrue(f.channel.isOpen());
            f.channel.writeInbound(f.pong(PLAY, 7));
            ((ByteBuf) f.channel.readInbound()).release();
            assertEquals(List.of(7), received);
            assertSame(decoder, f.channel.pipeline().get(CultDecoder.NAME));
            assertSame(encoder, f.channel.pipeline().get(CultEncoder.NAME));
        }
    }

    @Test
    void writesAfterCloseReleaseFramesAndFailPromises() throws Exception {
        try (var f = new Fixture(false)) {
            f.channel.close();
            var out = f.ping(PLAY, 3);
            var promise = f.channel.writeAndFlush(out);
            assertTrue(promise.isDone());
            assertFalse(promise.isSuccess());
            assertEquals(0, out.refCnt());
        }
    }

    @Test
    void healthyRemovalFollowsCompletedWritesAndPreservesNativeHandlers() throws Exception {
        try (var f = new Fixture(false)) {
            var nativeDecoder = f.channel.pipeline().get("decoder");
            var nativeEncoder = f.channel.pipeline().get("encoder");
            f.routes.send(ClientboundPackets.PING, event -> f.user.write(new ClientboundPing(9)));
            var one = f.channel.writeAndFlush(f.ping(PLAY, 1));
            var two = f.channel.writeAndFlush(f.ping(PLAY, 2));
            var removal = f.connection
                    .removeHandlers(() -> {
                        f.channel.pipeline().remove(CultDecoder.NAME);
                        f.channel.pipeline().remove(CultEncoder.NAME);
                    })
                    .toCompletableFuture();
            assertTrue(one.isSuccess());
            assertTrue(two.isSuccess());
            assertTrue(removal.isDone());
            assertTrue(
                    f.user.write(new ClientboundPing(3)).toCompletableFuture().isCompletedExceptionally());
            assertEquals(List.of("P9", "P1", "P9", "P2"), f.output());
            assertSame(nativeDecoder, f.channel.pipeline().get("decoder"));
            assertSame(nativeEncoder, f.channel.pipeline().get("encoder"));
        }
    }

    @Test
    void reloadInPlayResolvesTheExistingOwnerOnceWithoutMovingNativeHandlers() throws Exception {
        try (var f = new Fixture(true, false)) {
            var decoder = f.channel.pipeline().get("decoder");
            var encoder = f.channel.pipeline().get("encoder");
            f.connection.resolveOwner();
            assertSame(f.owner, f.connection.owner());
            f.routes.receive(ServerboundPackets.PONG, event -> f.assertOwner());
            f.channel.writeInbound(f.pong(PLAY, 1));
            f.pump();
            ((ByteBuf) f.channel.readInbound()).release();
            f.connection.resolveOwner();
            assertEquals(1, f.ownerLookups.get());
            assertSame(decoder, f.channel.pipeline().get("decoder"));
            assertSame(encoder, f.channel.pipeline().get("encoder"));
        }
    }

    @Test
    void outboundFramesControlsAndFlushKeepSynchronousOrder() throws Exception {
        try (var f = new Fixture(false)) {
            var trace = new ArrayList<String>();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    f.assertOwner();
                    trace.add(message instanceof ByteBuf bytes ? f.name(bytes) : "control");
                    ctx.write(message, promise);
                }

                public void flush(ChannelHandlerContext ctx) {
                    trace.add("flush");
                    ctx.flush();
                }
            });
            f.channel.write(f.ping(PLAY, 1));
            f.channel.write(f.ping(PLAY, 2));
            var control = new Object();
            var promise = f.channel.write(control);
            f.channel.flush();
            assertEquals(List.of("P1", "P2", "control", "flush"), trace);
            assertTrue(promise.isSuccess());
            ((ByteBuf) f.channel.readOutbound()).release();
            ((ByteBuf) f.channel.readOutbound()).release();
            assertSame(control, f.channel.readOutbound());
        }
    }

    @Test
    void hostCallbacksFollowQueuedPacketsAndCaptureTheirProofsBeforeLaterWrites() throws Exception {
        try (var f = new Fixture(true)) {
            var sequence = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> {
                f.assertOwner();
                if (event.getPacket().id() >= 100) {
                    event.getWritesAfterSend()
                            .add(new CultWrite(new ClientboundPing(sequence.incrementAndGet()), false));
                }
            });
            try {
                f.channel.write(f.ping(PLAY, 100));
                f.channel.write(f.ping(PLAY, 200));
                var ctx = f.channel.pipeline().context(CultEncoder.NAME);
                ((CultEncoder) ctx.handler()).executeAfterWrites(ctx, () -> {
                    f.assertOwner();
                    assertEquals(2, sequence.get(), "Both earlier packets must prepare their proof first");
                    f.user.write(new ClientboundPing(sequence.incrementAndGet()));
                });
                f.channel.writeAndFlush(f.ping(PLAY, 300));
                f.pump();
                assertEquals(
                        List.of("D", "P100", "P1", "D", "D", "P200", "P2", "D", "P3", "D", "P300", "P4", "D"),
                        f.output());
            } finally {
                f.channel.runPendingTasks();
            }
        }
    }

    @Test
    void modelScheduledProofsAllocateAtTheirOutboundQueuePosition() throws Exception {
        try (var f = new Fixture(true)) {
            var sequence = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> {
                f.assertOwner();
                if (event.getPacket().id() >= 100) {
                    event.getWritesAfterSend()
                            .add(new CultWrite(new ClientboundPing(sequence.incrementAndGet()), false));
                }
            });
            try {
                f.channel.write(f.ping(PLAY, 100));
                f.channel.writeAndFlush(f.ping(PLAY, 200));
                // A scheduled model task can run while I/O still holds packet 200.
                f.user.executeAfterWrites(() -> {
                    f.assertOwner();
                    f.user.write(new ClientboundPing(sequence.incrementAndGet()));
                });
                f.pump();
                assertEquals(List.of("D", "P100", "P1", "D", "D", "P200", "P2", "D", "P3"), f.output());
            } finally {
                f.channel.runPendingTasks();
            }
        }
    }

    @Test
    void afterSendProofsPrecedeProofsCreatedByFollowingQueuedPackets() throws Exception {
        try (var f = new Fixture(true)) {
            var sequence = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> {
                f.assertOwner();
                if (event.getPacket().id() >= 100) {
                    event.getWritesAfterSend()
                            .add(new CultWrite(new ClientboundPing(sequence.incrementAndGet()), false));
                    event.getTasksAfterSend().add(() -> f.user.write(new ClientboundPing(sequence.incrementAndGet())));
                }
            });
            try {
                f.channel.write(f.ping(PLAY, 100));
                f.channel.writeAndFlush(f.ping(PLAY, 200));
                f.pump();
                assertEquals(List.of("D", "P100", "P1", "D", "P2", "D", "P200", "P3", "D", "P4"), f.output());
            } finally {
                f.channel.runPendingTasks();
            }
        }
    }

    @Test
    void writesQueuedDuringEmissionDrainWithoutGrowingTheCallStack() throws Exception {
        try (var f = new Fixture(false)) {
            int count = 4096;
            var promises = new ArrayList<ChannelFuture>();
            f.routes.send(ClientboundPackets.PING, event -> f.assertOwner());
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    ByteBuf view = ((ByteBuf) message).duplicate();
                    Wire.readVarInt(view);
                    int id = view.readInt();
                    if (id + 1 < count) promises.add(f.channel.write(f.ping(PLAY, id + 1)));
                    ctx.write(message, promise);
                }
            });
            promises.add(f.channel.writeAndFlush(f.ping(PLAY, 0)));
            f.pump();
            assertEquals(count, promises.size());
            for (ChannelFuture promise : promises)
                assertTrue(promise.isSuccess(), () -> String.valueOf(promise.cause()));
            assertEquals(
                    java.util.stream.IntStream.range(0, count)
                            .mapToObj(id -> "P" + id)
                            .toList(),
                    f.output());
            assertTrue(
                    f.connection.removeHandlers(() -> {}).toCompletableFuture().isDone());
        }
    }

    @Test
    void writesAndFlushesQueuedDuringAFlushKeepTheirFifoPosition() throws Exception {
        try (var f = new Fixture(false)) {
            var trace = new ArrayList<String>();
            var promises = new ArrayList<ChannelFuture>();
            f.routes.send(ClientboundPackets.PING, event -> f.assertOwner());
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                boolean injected;

                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    String name = f.name((ByteBuf) message);
                    trace.add(name);
                    if (name.equals("P1")) {
                        promises.add(f.channel.write(f.ping(PLAY, 2)));
                        f.channel.flush();
                        promises.add(f.channel.write(f.ping(PLAY, 3)));
                    }
                    ctx.write(message, promise);
                }

                @Override
                public void flush(ChannelHandlerContext ctx) {
                    trace.add("flush");
                    if (!injected) {
                        injected = true;
                        promises.add(f.channel.writeAndFlush(f.ping(PLAY, 4)));
                    }
                    ctx.flush();
                }
            });
            promises.add(f.channel.write(f.ping(PLAY, 1)));
            f.pump();
            assertEquals(List.of("P1", "P2", "flush", "P3", "P4", "flush"), trace);
            for (ChannelFuture promise : promises)
                assertTrue(promise.isSuccess(), () -> String.valueOf(promise.cause()));
            assertEquals(List.of("P1", "P2", "P3", "P4"), f.output());
            assertTrue(
                    f.connection.removeHandlers(() -> {}).toCompletableFuture().isDone());
        }
    }

    @Test
    void failedInjectedDispatchReleasesPreparedChildrenAndForwardsTheOriginalParent() throws Exception {
        try (var f = new Fixture(false)) {
            var allocated = new ArrayList<ByteBuf>();
            f.channel.config().setAllocator(new AbstractByteBufAllocator(false) {
                @Override
                protected ByteBuf newHeapBuffer(int initial, int maximum) {
                    ByteBuf buffer = new UnpooledHeapByteBuf(this, initial, maximum);
                    allocated.add(buffer);
                    return buffer;
                }

                @Override
                protected ByteBuf newDirectBuffer(int initial, int maximum) {
                    ByteBuf buffer = new UnpooledDirectByteBuf(this, initial, maximum);
                    allocated.add(buffer);
                    return buffer;
                }

                @Override
                public boolean isDirectBufferPooled() {
                    return false;
                }
            });
            f.routes.send(ClientboundPackets.SET_PASSENGERS, event -> {
                event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(1), false));
                event.getWritesBeforeSend().add(new CultWrite(new ClientboundPing(2), false));
            });
            f.routes.send(ClientboundPackets.PING, event -> {
                if (event.getPacket().id() == 2) throw new IllegalArgumentException("Injected callback failure");
            });
            ByteBuf parent = f.frame(CLIENTBOUND, PLAY, "set_passengers");
            Wire.writeVarInt(parent, 7);
            Wire.writeVarInt(parent, 0);
            var promise = f.channel.newPromise();
            f.channel.writeAndFlush(parent, promise);
            f.channel.checkException();
            assertEquals(2, allocated.size());
            for (var buffer : allocated) assertEquals(0, buffer.refCnt());
            assertTrue(promise.isDone());
            assertTrue(promise.isSuccess());
            assertEquals(List.of("minecraft:set_passengers"), f.output());
            assertEquals(0, parent.refCnt());
            assertTrue(f.channel.isOpen());
            assertNotNull(f.channel.pipeline().get(CultDecoder.NAME));
            assertNotNull(f.channel.pipeline().get(CultEncoder.NAME));
            var later = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> later.incrementAndGet());
            assertTrue(f.channel.writeAndFlush(f.ping(PLAY, 3)).isSuccess());
            assertEquals(List.of("P3"), f.output());
            assertEquals(1, later.get());
        }
    }

    @Test
    void flushAfterWritesMatchesAnEmptyWriteTask() throws Exception {
        var scenarios = new LinkedHashMap<String, BiConsumer<Fixture, Runnable>>();
        scenarios.put("idle", (f, action) -> action.run());
        scenarios.put("unflushed frame", (f, action) -> {
            f.channel.write(f.ping(PLAY, 1));
            action.run();
        });
        scenarios.put("unflushed frames with proofs", (f, action) -> {
            var sequence = new AtomicInteger();
            f.routes.send(ClientboundPackets.PING, event -> {
                if (event.getPacket().id() >= 100) {
                    event.getWritesAfterSend()
                            .add(new CultWrite(new ClientboundPing(sequence.incrementAndGet()), false));
                }
            });
            f.channel.write(f.ping(PLAY, 100));
            action.run();
            f.channel.write(f.ping(PLAY, 200));
        });
        scenarios.put(
                "inside a write task",
                (f, action) -> f.user.executeAfterWrites(() -> {
                    f.user.write(new ClientboundPing(1));
                    action.run();
                    f.user.write(new ClientboundPing(2));
                }));
        scenarios.put("inside a send listener", (f, action) -> {
            f.routes.send(ClientboundPackets.PING, event -> {
                if (event.getPacket().id() == 1) action.run();
            });
            f.channel.write(f.ping(PLAY, 1));
        });
        scenarios.put("after close", (f, action) -> {
            f.channel.close();
            action.run();
        });
        scenarios.put("after handler removal", (f, action) -> {
            f.channel.write(f.ping(PLAY, 1));
            f.connection.removeHandlers(() -> {
                f.channel.pipeline().remove(CultDecoder.NAME);
                f.channel.pipeline().remove(CultEncoder.NAME);
            });
            action.run();
        });
        for (boolean owned : new boolean[] {true, false}) {
            for (var scenario : scenarios.entrySet()) {
                String at = scenario.getKey() + (owned ? " (owned)" : "");
                var task = transcript(owned, scenario.getValue(), f -> f.user.executeAfterWrites(() -> {}));
                var flush = transcript(owned, scenario.getValue(), f -> f.user.flushAfterWrites());
                assertEquals(task, flush, at);
                if (scenario.getKey().equals("unflushed frame"))
                    assertEquals(List.of("write P1", "flush", "outbound", "P1", "open true"), task, at);
            }
        }
    }

    /** Every write and flush reaching the head, then the outbound frames and whether the channel stayed open. */
    private static List<String> transcript(
            boolean owned, BiConsumer<Fixture, Runnable> scenario, Consumer<Fixture> action) {
        try (var f = new Fixture(owned)) {
            var log = new ArrayList<String>();
            f.channel.pipeline().addFirst("recorder", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    log.add("write "
                            + (message instanceof ByteBuf frame
                                    ? f.name(frame)
                                    : message.getClass().getSimpleName()));
                    ctx.write(message, promise);
                }

                @Override
                public void flush(ChannelHandlerContext ctx) {
                    log.add("flush");
                    ctx.flush();
                }
            });
            scenario.accept(f, () -> action.accept(f));
            f.pump();
            log.add("outbound");
            log.addAll(f.output());
            log.add("open " + f.channel.isOpen());
            return log;
        }
    }

    @FunctionalInterface
    private interface ConfigurationTask {
        void configure(ChannelHandlerContext context);
    }

    /** Downstream transport installs its next decoder through an ordered pipeline write. */
    private static final class ConfigurationInbound extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            if (message instanceof ByteBuf) {
                io.netty.util.ReferenceCountUtil.release(message);
                throw new io.netty.handler.codec.DecoderException("Packet reached an unconfigured decoder");
            }
            context.fireChannelRead(message);
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof ConfigurationTask task) {
                task.configure(context);
                promise.setSuccess();
            } else context.write(message, promise);
        }
    }

    @Test
    void theFlushBundleLeadsWithOnePingSharedUntilTheRealFlush() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "minecraft:set_camera", "D"), f.output());
            assertFalse(f.connection.flushBundleOpen());
            f.channel.writeAndFlush(wireCamera(f, 3));
            assertEquals(List.of("D", "P42", "minecraft:set_camera", "D"), f.output());
            assertEquals(List.of(41, 41, 42), proofs);
        }
    }

    @Test
    void aWriteTaskSeesThePingTheOpenFlushBundleShares() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            var seen = new ArrayList<Integer>();
            f.connection.executeAfterWrites(() -> seen.add(f.connection.provingPing()));
            f.channel.write(wireCamera(f, 1));
            f.connection.executeAfterWrites(() -> seen.add(f.connection.provingPing()));
            f.pump();
            assertEquals(List.of(0, 41), seen);
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D"), f.output());
            assertEquals(0, f.connection.provingPing());
        }
    }

    @Test
    void theLastPingProvesTheWireUntilAPacketFollowsItsBundle() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            var seen = new ArrayList<Integer>();
            f.channel.writeAndFlush(wireCamera(f, 1));
            f.pump();
            // The bundle has closed, and nothing has been written after it.
            f.connection.executeAfterWrites(() -> seen.add(f.connection.provingPing()));
            f.channel.writeAndFlush(f.frame(CLIENTBOUND, PLAY, "sound"));
            f.connection.executeAfterWrites(() -> seen.add(f.connection.provingPing()));
            f.pump();
            assertEquals(List.of(41, 0), seen);
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "minecraft:sound"), f.output());
        }
    }

    @Test
    void anUnlistenedPacketStaysInsideTheFlushBundle() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.frame(CLIENTBOUND, PLAY, "sound"));
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            assertEquals(
                    List.of("D", "P41", "minecraft:set_camera", "minecraft:sound", "minecraft:set_camera", "D"),
                    f.output());
            assertEquals(List.of(41, 41), proofs);
        }
    }

    @Test
    void aHostBundleJoinsTheOpenFlushBundle() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.delimiter());
            f.channel.write(f.ping(PLAY, 7));
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            // A client bundle cannot nest: the host's delimiters are dropped and its packets join the flush bundle.
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "P7", "minecraft:set_camera", "D"), f.output());
            assertEquals(List.of(41, 41), proofs);
        }
    }

    @Test
    void aProofInsideAHostBundleMakesItTheFlushBundle() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "minecraft:set_camera", "D"), f.output());
            assertEquals(List.of(41, 41), proofs);
        }
    }

    @Test
    void aRespawnLeavesAHeldHostBundleBeforeItsContinuationReopens() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            f.channel.write(structuralFrame(f, "respawn"));
            f.channel.write(f.delimiter());
            f.channel.flush();
            f.pump();
            // Even a held source bundle resumes only after its standalone respawn.
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "minecraft:respawn", "D", "D"), f.output());
        }
    }

    @Test
    void aRespawnClosesTheFlushBundle() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            // Cult keeps level/player replacement outside the preceding packet proof's bundle.
            f.channel.write(structuralFrame(f, "respawn"));
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            assertEquals(
                    List.of(
                            "D",
                            "P41",
                            "minecraft:set_camera",
                            "D",
                            "minecraft:respawn",
                            "D",
                            "P42",
                            "minecraft:set_camera",
                            "D"),
                    f.output());
            assertEquals(List.of(41, 42), proofs);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"login,false", "login,true", "respawn,false", "respawn,true"})
    void structuralPacketsResumeHostBundlesAfterTheirStandaloneFrame(String name, boolean hostStartedFirst) {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            if (hostStartedFirst) f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            if (!hostStartedFirst) f.channel.write(f.delimiter());
            ByteBuf structural = structuralFrame(f, name);
            var sent = f.channel.write(structural);
            assertFalse(f.connection.flushBundleOpen());
            f.channel.write(wireCamera(f, 2));
            f.channel.write(f.delimiter());
            f.channel.writeAndFlush(wireCamera(f, 3));
            f.pump();
            var emitted = f.output();
            assertEquals(
                    List.of(
                            "D",
                            "P41",
                            "minecraft:set_camera",
                            "D",
                            "minecraft:" + name,
                            "D",
                            "P42",
                            "minecraft:set_camera",
                            "minecraft:set_camera",
                            "D"),
                    emitted);
            assertStructuralOutsideBundles(emitted);
            assertEquals(List.of(41, 42, 42), proofs);
            assertTrue(sent.isSuccess());
            assertEquals(0, structural.refCnt());
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "login,false,false",
        "login,true,false",
        "respawn,false,false",
        "respawn,true,false",
        "login,false,true",
        "login,true,true",
        "respawn,false,true",
        "respawn,true,true"
    })
    void structuralPacketsStayStandaloneInAutomaticAndRequestedBundles(String name, boolean requested, boolean prove) {
        try (var f = new Fixture(false)) {
            var proofs = new ArrayList<Integer>();
            var next = new AtomicInteger(40);
            if (prove)
                f.routes.send(ClientboundPackets.ENTITY_MOTION, event -> {
                    int proof = event.flushPing();
                    if (proof == 0) {
                        proof = next.incrementAndGet();
                        event.leadWith(new CultWrite(new ClientboundPing(proof), false), proof);
                    }
                    proofs.add(proof);
                });
            Consumer<ac.cult.cultac.network.event.PacketSendEvent<?>> expand = event -> {
                assertEquals(0, event.flushPing());
                event.getWritesBeforeSend()
                        .add(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
                event.getWritesAfterSend()
                        .add(new CultWrite(new ClientboundEntityMotion(2, new Vec3d(0, 0, 0)), false));
                if (requested) event.bundle();
            };
            if (name.equals("login")) f.routes.send(ClientboundPackets.LOGIN, expand::accept);
            else f.routes.send(ClientboundPackets.RESPAWN, expand::accept);
            var sent = f.channel.writeAndFlush(structuralFrame(f, name));
            f.pump();
            var emitted = f.output();
            assertEquals(
                    prove
                            ? List.of(
                                    "D",
                                    "P41",
                                    "minecraft:set_entity_motion",
                                    "D",
                                    "minecraft:" + name,
                                    "D",
                                    "P42",
                                    "minecraft:set_entity_motion",
                                    "D")
                            : List.of(
                                    "D",
                                    "minecraft:set_entity_motion",
                                    "D",
                                    "minecraft:" + name,
                                    "D",
                                    "minecraft:set_entity_motion",
                                    "D"),
                    emitted);
            assertStructuralOutsideBundles(emitted);
            assertEquals(prove ? List.of(41, 42) : List.of(), proofs);
            assertTrue(sent.isSuccess(), () -> String.valueOf(sent.cause()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"login", "respawn"})
    void reentrantUnlistenedStructuralPacketsKeepTheirWirePosition(String name) {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            var promises = new ArrayList<ChannelFuture>();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                boolean injected;

                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    if (!injected && f.name((ByteBuf) message).equals("D")) {
                        injected = true;
                        promises.add(f.channel.write(structuralFrame(f, name)));
                    }
                    ctx.write(message, promise);
                }
            });
            f.channel.writeAndFlush(wireCamera(f, 1));
            f.pump();
            var emitted = f.output();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "minecraft:" + name), emitted);
            assertStructuralOutsideBundles(emitted);
            assertEquals(1, promises.size());
            assertTrue(promises.getFirst().isSuccess());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"login,false", "login,true", "respawn,false", "respawn,true"})
    void structuralTrailingProofDoesNotClaimBundleAtomicity(String name, boolean translated) throws Exception {
        try (var services = new RecordConsumerServices();
                var f = translated
                        ? RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)
                        : new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            f.user.bind(mock(ac.cult.cultac.platform.api.player.PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            var claims = new ArrayList<Boolean>();
            var tasks = new AtomicInteger();
            f.registrar.register(routes -> {
                routes.registerSendListener(
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        new ac.cult.cultac.events.packets.listeners.PacketPingListener());
                routes.send(
                        ClientboundPackets.SET_CAMERA,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) -> player.appendFlushProofTransaction(event));
                routes.send(
                        ClientboundPackets.LOGIN,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) ->
                                claims.add(player.runAtTrailingProof(event, tasks::incrementAndGet)));
                routes.send(
                        ClientboundPackets.RESPAWN,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) ->
                                claims.add(player.runAtTrailingProof(event, tasks::incrementAndGet)));
                // An ordinary inert packet retains the helper's bundle-atomic guarantee.
                routes.send(
                        ClientboundPackets.ENTITY_MOTION,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) ->
                                claims.add(player.runAtTrailingProof(event, tasks::incrementAndGet)));
            });
            f.channel.write(f.id(ClientboundPackets.BUNDLE_DELIMITER, "bundle_delimiter"));
            ByteBuf camera = f.id(ClientboundPackets.SET_CAMERA, "set_camera");
            Wire.writeVarInt(camera, 1);
            f.channel.write(camera);
            var type = name.equals("login") ? ClientboundPackets.LOGIN : ClientboundPackets.RESPAWN;
            ByteBuf structural = structuralPayload(f.id(type, name), name, !translated);
            var sent = f.channel.write(structural);
            f.connection.write(new CultWrite(new ClientboundEntityMotion(2, new Vec3d(0, 0, 0)), false));
            f.channel.writeAndFlush(f.id(ClientboundPackets.BUNDLE_DELIMITER, "bundle_delimiter"));
            for (int i = 0; i < 8; i++) f.channel.runPendingTasks();
            f.channel.checkException();
            var emitted = new ArrayList<String>();
            var pingIds = new HashSet<Integer>();
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                try {
                    String packet = f.data.packets(PLAY, CLIENTBOUND).name(Wire.readVarInt(frame));
                    if (packet.equals("minecraft:ping")) {
                        assertTrue(pingIds.add(frame.readInt()));
                        emitted.add("P");
                    } else emitted.add(packet.equals("minecraft:bundle_delimiter") ? "D" : packet);
                } finally {
                    frame.release();
                }
            }
            assertEquals(
                    List.of(
                            "D",
                            "P",
                            "minecraft:set_camera",
                            "D",
                            "minecraft:" + name,
                            "D",
                            "P",
                            "minecraft:set_entity_motion",
                            "P",
                            "D"),
                    emitted);
            assertStructuralOutsideBundles(emitted);
            assertEquals(List.of(false, true), claims);
            assertEquals(3, pingIds.size());
            assertEquals(0, tasks.get(), "Outbound proofs must not acknowledge or execute their receive tasks");
            assertEquals(0, f.player.lastTransactionReceived.get());
            assertTrue(sent.isSuccess());
            assertEquals(0, structural.refCnt());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void protocolTerminalRetiresOnlyItsKnownOldScopeDelimiter(boolean queuedOldClose) {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            var emitted = new ArrayList<String>();
            var oldClose = f.delimiter();
            var promises = new ArrayList<ChannelFuture>();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    var bytes = ((ByteBuf) message).duplicate();
                    var phase = f.connection.phase(CLIENTBOUND);
                    String name = RUNTIME.data().packets(phase, CLIENTBOUND).name(Wire.readVarInt(bytes));
                    emitted.add(name.equals("minecraft:bundle_delimiter") ? "D" : name);
                    if (queuedOldClose && name.equals("minecraft:start_configuration"))
                        promises.add(f.channel.write(oldClose));
                    ctx.write(message, promise);
                }
            });
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            var terminal = f.channel.writeAndFlush(f.frame(CLIENTBOUND, PLAY, "start_configuration"));
            assertEquals(CONFIGURATION, f.connection.phase(CLIENTBOUND));
            assertFalse(f.connection.flushBundleOpen());
            assertFalse(f.connection.clientHasLevel());
            var proofsAfterTerminal = new ArrayList<Integer>();
            f.connection.executeAfterWrites(() -> proofsAfterTerminal.add(f.connection.provingPing()));
            assertEquals(List.of(0), proofsAfterTerminal);
            // Configuration ID zero is a different packet, not an old PLAY bundle marker.
            ByteBuf config = Unpooled.buffer();
            Wire.writeVarInt(config, 0);
            Wire.writeString(config, "minecraft:test", 32767);
            f.channel.write(config);
            f.channel.writeAndFlush(f.frame(CLIENTBOUND, CONFIGURATION, "finish_configuration"));
            assertEquals(PLAY, f.connection.phase(CLIENTBOUND));
            f.channel.write(structuralFrame(f, "login"));
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 2));
            f.channel.writeAndFlush(f.delimiter());
            f.pump();
            assertEquals(
                    List.of(
                            "D",
                            "minecraft:ping",
                            "minecraft:set_camera",
                            "D",
                            "minecraft:start_configuration",
                            RUNTIME.data().packets(CONFIGURATION, CLIENTBOUND).name(0),
                            "minecraft:finish_configuration",
                            "minecraft:login",
                            "D",
                            "minecraft:ping",
                            "minecraft:set_camera",
                            "D"),
                    emitted);
            assertStructuralOutsideBundles(emitted);
            assertTrue(terminal.isSuccess());
            if (queuedOldClose) {
                assertEquals(1, promises.size());
                assertTrue(promises.getFirst().isSuccess());
                assertEquals(0, oldClose.refCnt());
            } else oldClose.release();
            ByteBuf output;
            while ((output = f.channel.readOutbound()) != null) output.release();
        }
    }

    @Test
    void loginFinishedAndConfigurationFinishedRemainStandalone() {
        try (var f = new Fixture(false)) {
            f.connection.phase(CLIENTBOUND, LOGIN);
            ByteBuf loginFinished = f.frame(CLIENTBOUND, LOGIN, "login_finished");
            loginFinished.writeLong(0).writeLong(1);
            Wire.writeString(loginFinished, "BoundaryTest", 16);
            Wire.writeVarInt(loginFinished, 0);
            loginFinished.writeLong(0).writeLong(2); // 26.3 session UUID follows the profile.
            assertTrue(f.channel.writeAndFlush(loginFinished).isSuccess());
            assertEquals(CONFIGURATION, f.connection.phase(CLIENTBOUND));
            assertTrue(f.channel
                    .writeAndFlush(f.frame(CLIENTBOUND, CONFIGURATION, "finish_configuration"))
                    .isSuccess());
            assertEquals(PLAY, f.connection.phase(CLIENTBOUND));
            assertFalse(f.connection.clientHasLevel());
            ByteBuf first = f.channel.readOutbound();
            ByteBuf second = f.channel.readOutbound();
            try {
                assertSame(loginFinished, first);
                assertEquals(
                        "minecraft:login_finished",
                        RUNTIME.data().packets(LOGIN, CLIENTBOUND).name(Wire.readVarInt(first)));
                assertEquals(
                        "minecraft:finish_configuration",
                        RUNTIME.data().packets(CONFIGURATION, CLIENTBOUND).name(Wire.readVarInt(second)));
                assertNull(f.channel.readOutbound());
            } finally {
                first.release();
                second.release();
            }
        }
    }

    @Test
    void cancellingRespawnDoesNotSplitOrLeakTheFlushBundle() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.routes.send(ClientboundPackets.RESPAWN, event -> event.setCancelled(true));
            f.channel.write(wireCamera(f, 1));
            ByteBuf structural = structuralFrame(f, "respawn");
            var sent = f.channel.write(structural);
            f.channel.writeAndFlush(wireCamera(f, 2));
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "minecraft:set_camera", "D"), f.output());
            assertEquals(List.of(41, 41), proofs);
            assertTrue(sent.isSuccess());
            assertEquals(0, structural.refCnt());
        }
    }

    @Test
    void aProtocolTerminalCannotAllocateAnOldPhaseTrailingProof() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            f.user.bind(mock(ac.cult.cultac.platform.api.player.PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            var calls = new AtomicInteger();
            f.registrar.register(routes -> routes.send(
                    ClientboundPackets.START_CONFIGURATION,
                    ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                    (event, player, packet) -> {
                        assertEquals(-1, player.appendFlushProofTransaction(event));
                        assertNull(player.appendTrailingProofTransaction(event));
                        calls.incrementAndGet();
                    }));
            assertTrue(f.channel
                    .writeAndFlush(f.id(ClientboundPackets.START_CONFIGURATION, "start_configuration"))
                    .isSuccess());
            for (int i = 0; i < 8; i++) f.channel.runPendingTasks();
            f.channel.checkException();
            assertEquals(1, calls.get());
            assertEquals(CONFIGURATION, f.connection.phase(CLIENTBOUND));
            assertEquals(0, f.player.lastTransactionSent.get());
            ByteBuf terminal = f.channel.readOutbound();
            try {
                assertEquals(
                        "minecraft:start_configuration",
                        f.data.packets(PLAY, CLIENTBOUND).name(Wire.readVarInt(terminal)));
                assertNull(f.channel.readOutbound());
            } finally {
                terminal.release();
            }
        }
    }

    private static ByteBuf structuralFrame(Fixture f, String name) {
        return structuralPayload(f.frame(CLIENTBOUND, PLAY, name), name, true);
    }

    private static ByteBuf structuralPayload(ByteBuf frame, String name, boolean modern) {
        if (name.equals("login")) {
            frame.writeInt(1).writeBoolean(false);
            Wire.writeVarInt(frame, 1);
            Wire.writeString(frame, "minecraft:overworld", 32767);
            Wire.writeVarInt(frame, 20);
            Wire.writeVarInt(frame, 10);
            Wire.writeVarInt(frame, 10);
            frame.writeBoolean(false).writeBoolean(true).writeBoolean(false);
        }
        Wire.writeVarInt(frame, 0);
        Wire.writeString(frame, "minecraft:overworld", 32767);
        frame.writeLong(0);
        if (modern) {
            Wire.writeVarInt(frame, 0);
            Wire.writeVarInt(frame, 0);
        } else frame.writeByte(0).writeByte(-1);
        frame.writeBoolean(false).writeBoolean(false).writeBoolean(false);
        Wire.writeVarInt(frame, 0);
        Wire.writeVarInt(frame, 63);
        if (name.equals("respawn")) frame.writeByte(0);
        else {
            if (modern) frame.writeBoolean(false); // 26.3 online mode.
            frame.writeBoolean(false); // Enforces secure chat.
        }
        return frame;
    }

    private static void assertStructuralOutsideBundles(List<String> emitted) {
        boolean inside = false;
        for (String name : emitted) {
            if (name.equals("D")) inside = !inside;
            else if (List.of(
                            "minecraft:login",
                            "minecraft:respawn",
                            "minecraft:start_configuration",
                            "minecraft:finish_configuration",
                            "minecraft:login_finished")
                    .contains(name)) assertFalse(inside, name + " must be outside a physical bundle: " + emitted);
        }
        assertFalse(inside, "Every resumed source bundle must have its closer: " + emitted);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void keepAliveClosesAFlushWaveAndStartsAFreshProofScope(boolean listened) {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            if (listened) f.routes.send(ClientboundPackets.KEEP_ALIVE, event -> assertEquals(0, event.flushPing()));
            var proving = new ArrayList<Integer>();
            f.channel.write(wireCamera(f, 1));
            ByteBuf keepAlive = f.keepAlive(101);
            var sent = f.channel.write(keepAlive);
            f.channel.write(new CultConnection.WriteTask(() -> proving.add(f.connection.provingPing())));
            f.channel.write(wireCamera(f, 2));
            f.channel.flush();
            f.pump();
            var emitted = f.output();
            assertEquals(
                    List.of("D", "P41", "minecraft:set_camera", "D", "K101", "D", "P42", "minecraft:set_camera", "D"),
                    emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(List.of(41, 42), proofs);
            assertEquals(List.of(0), proving);
            assertTrue(sent.isSuccess());
            assertEquals(0, keepAlive.refCnt());
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void keepAliveResumesAHeldHostBundleOnlyAfterItsStandaloneFrame(boolean hostStartedFirst) {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            if (hostStartedFirst) f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            if (!hostStartedFirst) f.channel.write(f.delimiter());
            f.channel.write(f.keepAlive(101));
            assertFalse(f.connection.flushBundleOpen());
            f.channel.write(wireCamera(f, 2));
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 3));
            f.channel.flush();
            f.pump();
            var emitted = f.output();
            assertEquals(
                    List.of(
                            "D",
                            "P41",
                            "minecraft:set_camera",
                            "D",
                            "K101",
                            "D",
                            "P42",
                            "minecraft:set_camera",
                            "minecraft:set_camera",
                            "D"),
                    emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(List.of(41, 42, 42), proofs);
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @Test
    void keepAliveSplitsASourceBundleWithoutAFlushOwner() {
        try (var f = new Fixture(false)) {
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.keepAlive(101));
            f.channel.write(wireCamera(f, 2));
            f.channel.writeAndFlush(f.delimiter());
            f.pump();
            var emitted = f.output();
            assertEquals(List.of("D", "minecraft:set_camera", "D", "K101", "D", "minecraft:set_camera", "D"), emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void keepAliveSplitsAnExplicitlyRequestedBundleWithoutSharingAcrossTheBoundary(boolean prove) {
        try (var f = new Fixture(false)) {
            var proofs = new ArrayList<Integer>();
            var next = new AtomicInteger(40);
            if (prove)
                f.routes.send(ClientboundPackets.ENTITY_MOTION, event -> {
                    int proof = event.flushPing();
                    if (proof == 0) {
                        proof = next.incrementAndGet();
                        event.leadWith(new CultWrite(new ClientboundPing(proof), false), proof);
                    }
                    proofs.add(proof);
                });
            f.routes.send(ClientboundPackets.KEEP_ALIVE, event -> {
                event.getWritesBeforeSend()
                        .add(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
                event.getWritesAfterSend()
                        .add(new CultWrite(new ClientboundEntityMotion(2, new Vec3d(0, 0, 0)), false));
                event.bundle();
            });
            // KEEP_ALIVE is read-only: the real host frame, not an unsupported CultWrite, enters the wrapper.
            var sent = f.channel.writeAndFlush(f.keepAlive(101));
            f.pump();
            var emitted = f.output();
            assertEquals(
                    prove
                            ? List.of(
                                    "D",
                                    "P41",
                                    "minecraft:set_entity_motion",
                                    "D",
                                    "K101",
                                    "D",
                                    "P42",
                                    "minecraft:set_entity_motion",
                                    "D")
                            : List.of(
                                    "D",
                                    "minecraft:set_entity_motion",
                                    "D",
                                    "K101",
                                    "D",
                                    "minecraft:set_entity_motion",
                                    "D"),
                    emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(prove ? List.of(41, 42) : List.of(), proofs);
            assertTrue(sent.isSuccess(), () -> String.valueOf(sent.cause()));
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @Test
    void keepAliveStaysStandaloneWhenItsExpansionCreatesAnAutomaticBundle() {
        try (var f = new Fixture(false)) {
            f.routes.send(ClientboundPackets.KEEP_ALIVE, event -> {
                assertEquals(0, event.flushPing());
                event.getWritesBeforeSend()
                        .add(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
                event.getWritesAfterSend()
                        .add(new CultWrite(new ClientboundEntityMotion(2, new Vec3d(0, 0, 0)), false));
            });
            var sent = f.channel.writeAndFlush(f.keepAlive(101));
            f.pump();
            var emitted = f.output();
            assertEquals(
                    List.of("D", "minecraft:set_entity_motion", "D", "K101", "D", "minecraft:set_entity_motion", "D"),
                    emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertTrue(sent.isSuccess());
        }
    }

    @Test
    void keepAliveContinuationResetsThePhysicalMemberCountBeforeAFreshProof() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.channel.write(f.delimiter());
            for (int i = 0; i < 4094; i++) {
                f.channel.write(
                        f.frame(CLIENTBOUND, PLAY, "game_event").writeByte(7).writeFloat(1));
            }
            f.channel.write(f.keepAlive(101));
            f.channel.write(wireCamera(f, 1));
            f.channel.writeAndFlush(f.delimiter());
            f.pump();
            var emitted = f.output();
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(4101, emitted.size());
            assertEquals(
                    List.of("D", "K101", "D", "P41", "minecraft:set_camera", "D"),
                    emitted.subList(4095, emitted.size()));
            assertEquals(List.of(41), proofs);
        }
    }

    @Test
    void reentrantUnlistenedKeepAliveWaitsForTheFramesAlreadyBeingEmitted() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            var promises = new ArrayList<ChannelFuture>();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                boolean injected;

                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    if (!injected && f.name((ByteBuf) message).equals("D")) {
                        injected = true;
                        promises.add(f.channel.write(f.keepAlive(101)));
                    }
                    ctx.write(message, promise);
                }
            });
            f.channel.writeAndFlush(wireCamera(f, 1));
            f.pump();
            var emitted = f.output();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "K101"), emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(1, promises.size());
            assertTrue(promises.getFirst().isSuccess());
        }
    }

    @Test
    void cancellingKeepAliveDoesNotSplitTheFlushBundleOrLeakItsFrame() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            f.routes.send(ClientboundPackets.KEEP_ALIVE, event -> event.setCancelled(true));
            f.channel.write(wireCamera(f, 1));
            ByteBuf keepAlive = f.keepAlive(101);
            var sent = f.channel.write(keepAlive);
            f.channel.writeAndFlush(wireCamera(f, 2));
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "minecraft:set_camera", "D"), f.output());
            assertEquals(List.of(41, 41), proofs);
            assertTrue(sent.isSuccess());
            assertEquals(0, keepAlive.refCnt());
        }
    }

    @Test
    void removingTheEncoderAfterKeepAliveLeavesTheHostItsOwnClosingDelimiter() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(f.delimiter());
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.keepAlive(101));
            CultEncoder.uninstall(f.channel);
            // This source delimiter must still close exactly the resumed host continuation.
            f.channel.writeAndFlush(f.delimiter());
            f.pump();
            var emitted = f.output();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "K101", "D", "D"), emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void productionKeepAliveProcessorKeepsItsDistinctPingBracketAroundAStandaloneChallenge(boolean translated)
            throws Exception {
        try (var services = new RecordConsumerServices();
                var f = translated
                        ? RecordReceiveFixture.olderHost(ProtocolVersion.V1_21_11)
                        : new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            services.useNetwork(f.manager);
            f.user.bind(mock(ac.cult.cultac.platform.api.player.PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            var processor = new ac.cult.cultac.utils.latency.KeepAliveProcessor(f.player);
            var keepAliveCheck =
                    f.player.checkManager.getCheck(ac.cult.cultac.checks.impl.badpackets.BadPacketsO.class);
            f.registrar.register(routes -> {
                routes.registerSendListener(ac.cult.cultac.network.event.PacketListenerPriority.NORMAL, processor);
                routes.registerSendListener(ac.cult.cultac.network.event.PacketListenerPriority.NORMAL, keepAliveCheck);
                routes.registerSendListener(
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        new ac.cult.cultac.events.packets.listeners.PacketPingListener());
                routes.send(
                        ClientboundPackets.SET_CAMERA,
                        ac.cult.cultac.network.event.PacketListenerPriority.NORMAL,
                        (event, player, packet) -> player.appendFlushProofTransaction(event));
            });
            assertEquals(translated, f.connection.packets().translated());
            ByteBuf before = f.id(ClientboundPackets.SET_CAMERA, "set_camera");
            Wire.writeVarInt(before, 1);
            f.channel.write(before);
            var sent = f.channel.write(
                    f.id(ClientboundPackets.KEEP_ALIVE, "keep_alive").writeLong(101));
            ByteBuf after = f.id(ClientboundPackets.SET_CAMERA, "set_camera");
            Wire.writeVarInt(after, 2);
            f.channel.writeAndFlush(after);
            for (int i = 0; i < 8; i++) f.channel.runPendingTasks();
            f.channel.checkException();
            var emitted = new ArrayList<String>();
            var pingIds = new ArrayList<Integer>();
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                try {
                    String name = f.data.packets(PLAY, CLIENTBOUND).name(Wire.readVarInt(frame));
                    if (name.equals("minecraft:ping")) {
                        pingIds.add(frame.readInt());
                        emitted.add("P");
                    } else if (name.equals("minecraft:bundle_delimiter")) emitted.add("D");
                    else if (name.equals("minecraft:keep_alive")) emitted.add("K" + frame.readLong());
                    else emitted.add(name);
                } finally {
                    frame.release();
                }
            }
            assertEquals(
                    List.of(
                            "D",
                            "P",
                            "minecraft:set_camera",
                            "P",
                            "D",
                            "K101",
                            "P",
                            "D",
                            "P",
                            "minecraft:set_camera",
                            "D"),
                    emitted);
            assertKeepAlivesOutsideBundles(emitted);
            assertEquals(4, new HashSet<>(pingIds).size());
            assertEquals(0, f.player.lastTransactionReceived.get(), "Outbound KA must not acknowledge a transaction");
            assertNotNull(processor.getPingMap().get(101L));
            assertTrue(sent.isSuccess());

            var pendingField = ac.cult.cultac.checks.impl.badpackets.BadPacketsO.class.getDeclaredField("keepalives");
            pendingField.setAccessible(true);
            var pending = (java.util.Collection<?>) pendingField.get(keepAliveCheck);
            var pongListener = new ac.cult.cultac.events.packets.listeners.PacketPingListener();
            var tickEnd = new ac.cult.cultac.protocol.packet.Opaque(ServerboundPackets.CLIENT_TICK_END);
            var tickEvent = new ac.cult.cultac.network.event.PacketReceiveEvent<>(
                    f.user, PLAY, ServerboundPackets.CLIENT_TICK_END, tickEnd);
            for (int i = 0; i < 3; i++) {
                var pong = new ac.cult.cultac.protocol.packet.serverbound.ServerboundPong(pingIds.get(i));
                pongListener.onPong(
                        new ac.cult.cultac.network.event.PacketReceiveEvent<>(
                                f.user, PLAY, ServerboundPackets.PONG, pong),
                        f.player,
                        pong);
                assertEquals(1, pending.size(), "a pong alone cannot retire a deferred reply");
                if (i == 1) {
                    keepAliveCheck.onClientTickEnd(tickEvent, f.player, tickEnd);
                    assertEquals(1, pending.size(), "the leading ping does not prove the standalone challenge");
                }
            }
            keepAliveCheck.onClientTickEnd(tickEvent, f.player, tickEnd);
            assertEquals(0, pending.size(), "the tick after the actual trailing pong retires the challenge");
            assertEquals(0, keepAliveCheck.getViolations());
        }
    }

    private static void assertKeepAlivesOutsideBundles(List<String> emitted) {
        boolean inside = false;
        int members = 0;
        for (String name : emitted) {
            if (name.equals("D")) {
                inside = !inside;
                members = 0;
            } else {
                if (name.startsWith("K")) assertFalse(inside, "Keepalive must retain its standalone dispatch path");
                if (inside) assertTrue(++members <= 4096, "A physical bundle holds at most 4,096 packets");
            }
        }
        assertFalse(inside, "All host and generated bundle continuations must close");
    }

    @Test
    void aBundleOpenedBeforeAStaleQueuedCloseRunsStillCloses() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            // EmbeddedChannel runs queued tasks after each write and flush reaching its head. Holding the frames
            // short of it keeps the first flush's queued close behind the later writes, as a host's event loop does.
            var held = new ArrayList<ByteBuf>();
            f.channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    held.add((ByteBuf) message);
                    promise.setSuccess();
                }

                @Override
                public void flush(ChannelHandlerContext ctx) {}
            });
            f.channel.pipeline().write(wireCamera(f, 1));
            f.channel.pipeline().flush();
            // The respawn closes the first bundle before its queued close runs.
            f.channel.pipeline().write(f.frame(CLIENTBOUND, PLAY, "respawn"));
            f.channel.pipeline().write(wireCamera(f, 2));
            f.channel.pipeline().flush();
            f.pump();
            var names = new ArrayList<String>();
            for (ByteBuf frame : held) {
                names.add(f.name(frame));
                frame.release();
            }
            assertEquals(
                    List.of(
                            "D",
                            "P41",
                            "minecraft:set_camera",
                            "D",
                            "minecraft:respawn",
                            "D",
                            "P42",
                            "minecraft:set_camera",
                            "D"),
                    names);
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @Test
    void aLaterCultPingInTheBundleBecomesTheSharedPing() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            // Stands in for PacketPingListener, which tags CultAC's own pings with their transaction.
            f.routes.send(ClientboundPackets.PING, event -> {
                if (event.getPacket().id() >= 100)
                    event.trackedPing(event.getPacket().id());
            });
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.ping(PLAY, 7));
            f.channel.write(wireCamera(f, 2));
            f.channel.write(f.ping(PLAY, 100));
            f.channel.write(wireCamera(f, 3));
            f.channel.flush();
            f.pump();
            assertEquals(
                    List.of(
                            "D",
                            "P41",
                            "minecraft:set_camera",
                            "P7",
                            "minecraft:set_camera",
                            "P100",
                            "minecraft:set_camera",
                            "D"),
                    f.output());
            assertEquals(List.of(41, 41, 100), proofs);
        }
    }

    @Test
    void aBundledWriteGroupJoinsTheOpenFlushBundle() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            f.pump();
            var group = f.connection.write(List.of(new CultWrite(new ClientboundPing(9), false)), true);
            f.pump();
            f.channel.flush();
            f.pump();
            assertTrue(group.isSuccess());
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "P9", "D"), f.output());
        }
    }

    @Test
    void anOversizedFlushBundleSplitsWithAFreshLeadingPing() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            for (int id = 0; id < 4096; id++) f.channel.write(wireCamera(f, id));
            f.channel.flush();
            f.pump();
            List<String> emitted = f.output();
            // With 16 slots of sharing headroom, the lead and 4,079 cameras fill the first proof scope.
            assertEquals(4102, emitted.size());
            assertEquals(List.of("D", "P41"), emitted.subList(0, 2));
            assertEquals(List.of("D", "D", "P42"), emitted.subList(4081, 4084));
            assertEquals("D", emitted.get(4101));
            assertEquals(4079, proofs.stream().filter(proof -> proof == 41).count());
            assertEquals(17, proofs.stream().filter(proof -> proof == 42).count());
        }
    }

    @Test
    void aLeadAdoptingAFullHostBundleStaysWithItsPacket() {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            // A valid host bundle: 4,095 unlistened packets, then one CultAC proves.
            f.channel.write(f.delimiter());
            for (int i = 0; i < 4095; i++) {
                f.channel.write(
                        f.frame(CLIENTBOUND, PLAY, "game_event").writeByte(7).writeFloat(1));
            }
            f.channel.write(wireCamera(f, 1));
            f.channel.write(f.delimiter());
            f.channel.flush();
            f.pump();
            List<String> emitted = f.output();
            assertEquals(List.of(41), proofs);
            int ping = emitted.indexOf("P41");
            // ClientPacketListener#handleBundlePacket answers the ping in the camera's task only within one bundle.
            assertEquals("minecraft:set_camera", emitted.get(ping + 1));
            int members = 0;
            for (String name : emitted) {
                if (name.equals("D")) members = 0;
                else assertTrue(++members <= 4096, "A client bundle holds at most 4,096 packets");
            }
        }
    }

    @Test
    void removingTheEncoderClosesAnOpenFlushBundle() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            f.pump();
            CultEncoder.uninstall(f.channel);
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D"), f.output());
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    /**
     * Each camera is proven as CultPlayer#appendFlushProofTransaction proves an update: it shares the bundle's
     * ping, or leads with a new one. Returns the transactions in packet order.
     */
    private static List<Integer> proveCameras(Fixture f) {
        var proofs = new ArrayList<Integer>();
        var next = new AtomicInteger(40);
        f.routes.send(ClientboundPackets.SET_CAMERA, event -> {
            int proof = event.flushPing();
            if (proof == 0) {
                proof = next.incrementAndGet();
                event.leadWith(new CultWrite(new ClientboundPing(proof), false), proof);
            }
            proofs.add(proof);
        });
        return proofs;
    }

    private static ByteBuf wireCamera(Fixture f, int entityId) {
        ByteBuf buffer = f.frame(CLIENTBOUND, PLAY, "set_camera");
        Wire.writeVarInt(buffer, entityId);
        return buffer;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aReentrantWriteDuringFlushClosureGetsANewProof(boolean standalone) {
        try (var f = new Fixture(false)) {
            var proofs = proveCameras(f);
            var delimiters = new AtomicInteger();
            f.channel.pipeline().addBefore(CultEncoder.NAME, "reentrant-close", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                    boolean closing = message instanceof ByteBuf bytes
                            && f.name(bytes).equals("D")
                            && delimiters.incrementAndGet() == 2;
                    ctx.write(message, promise);
                    if (closing) f.channel.write(wireCamera(f, 2));
                }
            });
            f.channel.write(wireCamera(f, 1));
            if (standalone) f.channel.write(f.keepAlive(101));
            f.channel.flush();
            f.pump();
            f.channel.flush();
            f.pump();
            assertEquals(List.of(41, 42), proofs);
            var emitted = f.output();
            assertEquals(
                    2,
                    emitted.stream()
                            .filter(name -> name.equals("P41") || name.equals("P42"))
                            .count());
            assertKeepAlivesOutsideBundles(emitted);
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @Test
    void transferIsWrittenOutsideTheFlushBundle() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            var transfer = f.frame(CLIENTBOUND, PLAY, "transfer");
            Wire.writeString(transfer, "localhost", 32767);
            Wire.writeVarInt(transfer, 25565);
            f.channel.write(transfer);
            f.channel.flush();
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "minecraft:transfer"), f.output());
            assertFalse(f.connection.flushBundleOpen());
        }
    }

    @Test
    void disconnectClosesTheBundleBeforeItsCompletionCanCloseTheChannel() {
        try (var f = new Fixture(false)) {
            proveCameras(f);
            f.channel.write(wireCamera(f, 1));
            var completion = f.channel.newPromise();
            completion.addListener(ignored -> f.channel.close());
            // The raw packet retains the host's completion callback even without a model send route.
            f.channel
                    .pipeline()
                    .write(
                            f.frame(CLIENTBOUND, PLAY, "disconnect")
                                    .writeByte(10)
                                    .writeByte(0),
                            completion);
            f.channel.flush();
            f.pump();
            assertEquals(List.of("D", "P41", "minecraft:set_camera", "D", "minecraft:disconnect"), f.output());
            assertFalse(f.channel.isOpen());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final TestTransportRoutes routes = new TestTransportRoutes(RUNTIME);
        final io.netty.util.concurrent.EventExecutor owner;
        final AtomicInteger ownerLookups = new AtomicInteger();
        final CultConnection connection;
        final User user;

        Fixture(boolean owned) {
            this(owned, true);
        }

        Fixture(boolean owned, boolean selectOwner) {
            owner = channel.eventLoop();
            connection = new CultConnection(channel, routes.dispatcher, ignored -> {
                ownerLookups.incrementAndGet();
                return owner == null ? null : new PacketOwner(owner, null);
            });
            connection.phase(SERVERBOUND, PLAY);
            connection.phase(CLIENTBOUND, PLAY);
            user = new User(new User.Profile(UUID.randomUUID(), "TransportTest"), connection);
            channel.pipeline().addLast("flow", new FlowControlHandler());
            channel.pipeline().addLast("decoder", nativeDecoder());
            channel.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
            CultDecoder.install(connection);
            CultEncoder.install(connection);
            // Match production: select the owner once when the first configuration frame arrives.
            if (owned && selectOwner) {
                connection.phase(SERVERBOUND, CONFIGURATION);
                channel.writeInbound(pong(CONFIGURATION, 0));
                pump();
                ((ByteBuf) channel.readInbound()).release();
                connection.phase(SERVERBOUND, PLAY);
            }
        }

        void assertOwner() {
            assertTrue(owner == null ? channel.eventLoop().inEventLoop() : owner.inEventLoop());
        }

        void pump() {
            for (int i = 0; i < 8; i++) {
                channel.runPendingTasks();
            }
            channel.checkException();
        }

        ChannelInboundHandlerAdapter nativeDecoder() {
            return new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    assertOwner();
                    if (message instanceof ByteBuf frame) {
                        ByteBuf view = frame.duplicate();
                        int id = Wire.readVarInt(view);
                        ConnectionPhase phase = connection.phase(SERVERBOUND);
                        String name = RUNTIME.data().packets(phase, SERVERBOUND).name(id);
                        boolean terminal = List.of(
                                        "minecraft:login_acknowledged",
                                        "minecraft:finish_configuration",
                                        "minecraft:configuration_acknowledged")
                                .contains(name);
                        // Vanilla has decoded the bytes before its listener changes the pipeline.
                        ctx.fireChannelRead(message);
                        if (terminal) {
                            ctx.pipeline().replace(ctx.name(), "inbound_config", new ConfigurationInbound());
                            ctx.channel().config().setAutoRead(false);
                        }
                    } else ctx.fireChannelRead(message);
                }
            };
        }

        void configureInbound() {
            if (channel.pipeline().get("inbound_config") == null) return;
            ConfigurationTask task = ctx -> {
                ctx.pipeline().replace(ctx.name(), "decoder", nativeDecoder());
                ctx.channel().config().setAutoRead(true);
            };
            assertTrue(channel.writeAndFlush(task).isSuccess());
        }

        ByteBuf frame(PacketDirection direction, ConnectionPhase phase, String name) {
            ByteBuf frame = Unpooled.buffer();
            Wire.writeVarInt(
                    frame,
                    RUNTIME.data()
                            .packets(phase, direction)
                            .id(name.startsWith("minecraft:") ? name : "minecraft:" + name));
            return frame;
        }

        ByteBuf ping(ConnectionPhase phase, int id) {
            return frame(CLIENTBOUND, phase, "ping").writeInt(id);
        }

        ByteBuf keepAlive(long id) {
            return frame(CLIENTBOUND, PLAY, "keep_alive").writeLong(id);
        }

        ByteBuf pong(ConnectionPhase phase, int id) {
            return frame(SERVERBOUND, phase, "pong").writeInt(id);
        }

        ByteBuf delimiter() {
            return frame(CLIENTBOUND, PLAY, "bundle_delimiter");
        }

        String name(ByteBuf frame) {
            ByteBuf view = frame.duplicate();
            String name = RUNTIME.data().packets(PLAY, CLIENTBOUND).name(Wire.readVarInt(view));
            return name.equals("minecraft:bundle_delimiter")
                    ? "D"
                    : name.equals("minecraft:ping")
                            ? "P" + view.readInt()
                            : name.equals("minecraft:keep_alive") ? "K" + view.readLong() : name;
        }

        String hex(ByteBuf frame) {
            try {
                return ByteBufUtil.hexDump(frame);
            } finally {
                frame.release();
            }
        }

        List<String> output() {
            var names = new ArrayList<String>();
            ByteBuf frame;
            while ((frame = channel.readOutbound()) != null) {
                try {
                    names.add(name(frame));
                } finally {
                    frame.release();
                }
            }
            return names;
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }
}
