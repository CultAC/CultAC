package ac.cult.cultac.network;

import static ac.cult.cultac.protocol.ConnectionPhase.*;
import static ac.cult.cultac.protocol.PacketDirection.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.netty.CultDecoder;
import ac.cult.cultac.protocol.netty.CultEncoder;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.UnconfiguredPipelineHandler;
import org.junit.jupiter.api.Test;

/** Replacement contract, written before the transport implementation (D1–D7, C1/C2). */
class CultTransportTest {
    private static final ProtocolRuntime RUNTIME = ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3));

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
            Object event = Class.forName("io.papermc.paper.network.ConnectionEvent")
                    .getField("COMPRESSION_THRESHOLD_SET")
                    .get(null);
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
            f.channel.pipeline().replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
            assertTrue(f.channel
                    .writeAndFlush(new CultWrite(new ClientboundPing(8), false))
                    .isSuccess());
            UnconfiguredPipelineHandler.InboundConfigurationTask task = ctx -> {
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
                    net.minecraft.SharedConstants.tryDetectVersion();
                    net.minecraft.server.Bootstrap.bootStrap();
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
    void malformedRoutedFramesCloseAndReleaseTheFrame() throws Exception {
        try (var f = new Fixture(true)) {
            f.routes.receive(ServerboundPackets.PONG, event -> fail("Truncated pong must not dispatch"));
            ByteBuf frame = f.frame(SERVERBOUND, PLAY, "pong");
            // EmbeddedChannel also drains pending tasks in writeInbound, so a
            // fast owner may deliver the same failure before pump is called.
            assertThrows(MalformedPacketException.class, () -> {
                f.channel.writeInbound(frame);
                f.pump();
            });
            assertFalse(f.channel.isOpen());
            assertEquals(0, frame.refCnt());
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
    void failedInjectedDispatchReleasesBothTheParentAndPreparedChildren() throws Exception {
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
            assertThrows(IllegalArgumentException.class, f.channel::checkException);
            assertEquals(2, allocated.size());
            for (var buffer : allocated) assertEquals(0, buffer.refCnt());
            assertEquals(0, parent.refCnt());
            assertTrue(promise.isDone());
            assertFalse(promise.isSuccess());
            assertNull(f.channel.readOutbound());
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
                            ctx.pipeline()
                                    .replace(ctx.name(), "inbound_config", new UnconfiguredPipelineHandler.Inbound());
                            ctx.channel().config().setAutoRead(false);
                        }
                    } else ctx.fireChannelRead(message);
                }
            };
        }

        void configureInbound() {
            if (channel.pipeline().get("inbound_config") == null) return;
            UnconfiguredPipelineHandler.InboundConfigurationTask task = ctx -> {
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
                    : name.equals("minecraft:ping") ? "P" + view.readInt() : name;
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
