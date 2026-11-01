package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.CaptureFixture.Host;
import ac.cult.cultac.cloud.capture.CaptureStream;
import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Outcome;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketRouteBuilder;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.value.PlayerAction;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CloudCaptureTapTest {
    private static final String BRIDGE = "cultac:bridge";

    /** The platform's own routes: held-slot changes are cancelled, and bridge frames never reach the client. */
    private static final Consumer<PacketRouteBuilder> ROUTES = routes -> {
        routes.receiveConnection(
                ServerboundPackets.SET_CARRIED_ITEM,
                PacketListenerPriority.NORMAL,
                (event, packet) -> event.setCancelled(true));
        routes.sendConnection(ClientboundPackets.CUSTOM_PAYLOAD, PacketListenerPriority.LOWEST, (event, packet) -> {
            if (BRIDGE.equals(packet.channel())) event.setCancelled(true);
        });
    };

    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    /** A player already in play when the capture installs, joined on socket {@code a}, every packet wanted. */
    private static CaptureFixture joined(Host host) {
        return joined(host, ROUTES);
    }

    private static CaptureFixture joined(Host host, Consumer<PacketRouteBuilder> routes) {
        return joined(host, routes, (player, bind, playing) -> bind.accept("a"));
    }

    private static CaptureFixture joined(Host host, Consumer<PacketRouteBuilder> routes, SegmentBinder binder) {
        CaptureFixture fixture = new CaptureFixture(host, true, routes);
        CloudCapture.segmentBinder(binder);
        fixture.install();
        FakeUplink a = fixture.uplinks.socket("a");
        assertEquals(List.of(IntakeMessage.KindCase.JOIN), a.kinds());
        a.captureAll(fixture.player.getUniqueId());
        return fixture;
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void tapsSitWhereTheBytesAreTheClients(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            List<String> names = fixture.channel.pipeline().names();
            String decoder = host == Host.PROXY ? "via-decoder" : "cult-decoder";
            String encoder = host == Host.PROXY ? "via-encoder" : "cult-encoder";
            assertEquals(names.indexOf(decoder) - 1, names.indexOf(CloudCapture.INBOUND));
            assertEquals(names.indexOf(encoder) - 1, names.indexOf(CloudCapture.OUTBOUND));

            ByteBuf sent = fixture.packet(PacketDirection.SERVERBOUND, "player_loaded");
            byte[] bytes = new byte[sent.readableBytes()];
            sent.getBytes(sent.readerIndex(), bytes);
            fixture.channel.writeInbound(sent);
            drain(fixture);
            fixture.tickEnd();
            assertTrue(serverbound(fixture).stream().anyMatch(record -> Arrays.equals(bytes, record)));
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void releasedTapsLeaveBeforeTheNextInstall(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            ChannelPipeline pipeline = fixture.channel.pipeline();
            CloudCapture.release(fixture.player);
            assertNull(pipeline.get(CloudCapture.INBOUND));
            assertNull(pipeline.get(CloudCapture.OUTBOUND));

            fixture.install();
            assertNotNull(pipeline.get(CloudCapture.INBOUND));
            assertNotNull(pipeline.get(CloudCapture.OUTBOUND));
            assertEquals(
                    List.of(IntakeMessage.KindCase.JOIN, IntakeMessage.KindCase.LEAVE, IntakeMessage.KindCase.JOIN),
                    fixture.uplinks.socket("a").kinds());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void capturesTurnedOffAreNotKeptByTheOpenChannel(Host host) throws InterruptedException {
        try (CaptureFixture fixture = new CaptureFixture(host, true, ROUTES)) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.player.observer = null;
            List<WeakReference<CloudCapture>> captures = new ArrayList<>();
            for (int cycle = 0; cycle < 3; cycle++) {
                fixture.uplinks.enabled = true;
                captures.add(installed(fixture));
                fixture.uplinks.enabled = false;
                CloudCapture.reconfigure(fixture.player);
                fixture.channel.runPendingTasks();
                assertNull(fixture.channel.pipeline().get(CloudCapture.INBOUND));
            }
            assertTrue(fixture.channel.isOpen());
            for (int attempt = 0; attempt < 10 && !collected(captures); attempt++) {
                System.gc();
                Thread.sleep(20);
            }
            assertTrue(collected(captures));
        }
    }

    private static boolean collected(List<WeakReference<CloudCapture>> captures) {
        return captures.stream().allMatch(capture -> capture.get() == null);
    }

    private static WeakReference<CloudCapture> installed(CaptureFixture fixture) {
        CloudCapture.reconfigure(fixture.player);
        fixture.channel.runPendingTasks();
        CloudCapture capture = CloudCapture.of(fixture.player);
        assertNotNull(capture);
        assertNotNull(fixture.channel.pipeline().get(CloudCapture.INBOUND));
        return new WeakReference<>(capture);
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void anInstallSkipsAPipelineWithAnotherRuntimesTaps(Host host) {
        try (CaptureFixture fixture = new CaptureFixture(host, true, ROUTES)) {
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            String decoder = host == Host.PROXY ? "via-decoder" : "cult-decoder";
            fixture.channel.pipeline().addBefore(decoder, CloudCapture.INBOUND, new ChannelInboundHandlerAdapter());
            fixture.install();
            assertTrue(fixture.uplinks.sockets.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aCancelledServerboundPacketIsStillCaptured(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            fixture.channel.writeInbound(
                    fixture.packet(PacketDirection.SERVERBOUND, "set_carried_item", (byte) 0, (byte) 4));
            assertNull(fixture.channel.readInbound());
            fixture.tickEnd();
            int id = fixture.id(PacketDirection.SERVERBOUND, "set_carried_item");
            assertTrue(serverbound(fixture).stream().anyMatch(record -> record[0] == id && record[2] == 4));
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aServerboundPacketPastTheIntakeLimitIsNotKept(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            int id = fixture.id(PacketDirection.SERVERBOUND, "player_loaded");
            byte[] limit = new byte[CaptureStream.MAX_SERVERBOUND_BYTES - 1];
            limit[0] = 1;
            byte[] over = new byte[CaptureStream.MAX_SERVERBOUND_BYTES];
            over[0] = 2;
            fixture.channel.writeInbound(fixture.packet(PacketDirection.SERVERBOUND, "player_loaded", limit));
            drain(fixture);
            fixture.channel.writeInbound(fixture.packet(PacketDirection.SERVERBOUND, "player_loaded", over));
            drain(fixture);
            fixture.tickEnd();
            List<byte[]> kept = serverbound(fixture).stream()
                    .filter(record -> record[0] == id)
                    .toList();
            assertEquals(1, kept.size());
            assertEquals(CaptureStream.MAX_SERVERBOUND_BYTES, kept.get(0).length);
            assertTrue(records(fixture).stream().noneMatch(Record::hasGap));
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void anInjectedPingIsCapturedAsLeading(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            fixture.player.sendTransaction();
            fixture.channel.runPendingTasks();
            drainOutbound(fixture);
            fixture.tickEnd();
            List<Record> pings = new ArrayList<>();
            for (Record record : records(fixture)) {
                if (record.getBodyCase() == Record.BodyCase.PING) pings.add(record);
            }
            assertEquals(1, pings.size());
            assertEquals(CultPlayer.PING_LEADING, pings.get(0).getPingValue());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void noPluginMessageIsEverCaptured(Host host) {
        try (CaptureFixture fixture = joined(host)) {
            fixture.user.write(new CultWrite(new ClientboundCustomPayload(BRIDGE, new byte[] {1, 2, 3}), false));
            fixture.user.write(new CultWrite(new ClientboundCustomPayload("minecraft:brand", new byte[] {4}), false));
            fixture.channel.runPendingTasks();
            drainOutbound(fixture);
            fixture.tickEnd();
            int payload = fixture.id(PacketDirection.CLIENTBOUND, "custom_payload");
            List<String> captured = new ArrayList<>();
            for (Record record : records(fixture)) {
                if (record.getBodyCase() != Record.BodyCase.CLIENTBOUND) continue;
                byte[] bytes = record.getClientbound().toByteArray();
                if (bytes[0] == payload) captured.add(new String(bytes, StandardCharsets.ISO_8859_1));
            }
            assertEquals(List.of(), captured);
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aSwapCultacDropsIsDroppedAndNamesItsRecord(Host host) {
        Consumer<PacketRouteBuilder> dropSwaps = routes -> routes.receiveConnection(
                ServerboundPackets.PLAYER_ACTION,
                PacketListenerPriority.NORMAL,
                (event, packet) -> event.setCancelled(packet.action() == PlayerAction.SWAP_ITEM_WITH_OFFHAND));
        try (CaptureFixture fixture = joined(host, ROUTES.andThen(dropSwaps))) {
            fixture.serverbound("player_loaded");
            fixture.serverbound(
                    "player_action",
                    (byte) 7,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0,
                    (byte) 0);
            fixture.tickEnd();
            fixture.tickEnd();

            List<Outcome> outcomes = outcomes(fixture);
            assertEquals(1, outcomes.size());
            Outcome swap = outcomes.get(0);
            assertEquals(Outcome.Type.TYPE_SWAP, swap.getType());
            assertEquals(Outcome.Status.STATUS_DROPPED, swap.getStatus());
            assertEquals(Outcome.Join.JOIN_CULTAC, swap.getJoin());
            Frame frame = frames(fixture).stream()
                    .filter(candidate -> candidate.getSeq() == swap.getActionFrame())
                    .findFirst()
                    .orElseThrow();
            List<Record> serverbound = frame.getRecordsList().stream()
                    .filter(record -> record.getBodyCase() == Record.BodyCase.SERVERBOUND)
                    .toList();
            byte[] action =
                    serverbound.get(swap.getActionIndex()).getServerbound().toByteArray();
            assertEquals(fixture.id(PacketDirection.SERVERBOUND, "player_action"), action[0]);
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aTotemPopOnTheirOwnEntityGoesOut(Host host) {
        Consumer<PacketRouteBuilder> engine =
                routes -> new CheckManagerListener().registerForwardingSendPackets(routes, PacketListenerPriority.LOW);
        try (CaptureFixture fixture = joined(host, ROUTES.andThen(engine))) {
            int self = fixture.player.entityID;
            fixture.clientbound(
                    "entity_event", (byte) (self >>> 24), (byte) (self >>> 16), (byte) (self >>> 8), (byte) self, (byte)
                            35);
            fixture.tickEnd();
            fixture.tickEnd();

            List<Outcome> outcomes = outcomes(fixture);
            assertEquals(1, outcomes.size());
            assertEquals(Outcome.Type.TYPE_POP, outcomes.get(0).getType());
            assertEquals(Outcome.Status.STATUS_APPLIED, outcomes.get(0).getStatus());
            assertFalse(outcomes.get(0).hasActionFrame());
        }
    }

    /** Binds every segment to {@code a} and records the markers sent; the backend answers them when {@code marks}. */
    private static final class MarkingBinder implements SegmentBinder {
        private final boolean marks;
        private final List<Integer> marked = new ArrayList<>();

        MarkingBinder(boolean marks) {
            this.marks = marks;
        }

        @Override
        public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
            bind.accept("a");
        }

        @Override
        public boolean marks(CultPlayer player) {
            return marks;
        }

        @Override
        public void mark(CultPlayer player, int marker) {
            marked.add(marker);
        }
    }

    /** CultAC reads held-slot changes and the server's held slot, and bridge frames never reach the client. */
    private static final Consumer<PacketRouteBuilder> HELD = routes -> {
        routes.receiveConnection(
                ServerboundPackets.SET_CARRIED_ITEM, PacketListenerPriority.NORMAL, (event, packet) -> {});
        routes.sendConnection(ClientboundPackets.SET_HELD_SLOT, PacketListenerPriority.NORMAL, (event, packet) -> {});
    };

    @ParameterizedTest
    @EnumSource(Host.class)
    void aHeldChangeIsMarkedAtTheTickEndAndAppliedWhenTheMarkerIsAnswered(Host host) {
        MarkingBinder binder = new MarkingBinder(true);
        try (CaptureFixture fixture = joined(host, HELD, binder)) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) 3);
            assertEquals(List.of(), binder.marked);
            fixture.tickEnd();
            assertEquals(List.of(0), binder.marked);
            fixture.tickEnd();
            assertEquals(List.of(0), binder.marked, "a tick with nothing awaiting sends no marker");

            CloudCapture.markerAnswered(fixture.player, 0);
            fixture.tickEnd();
            fixture.tickEnd();

            List<Outcome> outcomes = outcomes(fixture);
            assertEquals(1, outcomes.size());
            assertEquals(Outcome.Type.TYPE_HELD, outcomes.get(0).getType());
            assertEquals(Outcome.Status.STATUS_APPLIED, outcomes.get(0).getStatus());
            assertTrue(outcomes.get(0).hasActionIndex());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void theMarkerFollowsTheTickEndItWasSentFor(Host host) {
        List<String> order = new ArrayList<>();
        SegmentBinder binder = new SegmentBinder() {
            @Override
            public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
                bind.accept("a");
            }

            @Override
            public boolean marks(CultPlayer player) {
                return true;
            }

            @Override
            public void mark(CultPlayer player, int marker) {
                order.add("marker " + marker);
            }
        };
        try (CaptureFixture fixture = joined(host, HELD, binder)) {
            fixture.channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    if (message instanceof ByteBuf frame) order.add("forwarded " + frame.getByte(frame.readerIndex()));
                    ctx.fireChannelRead(message);
                }
            });
            byte held = (byte) fixture.id(PacketDirection.SERVERBOUND, "set_carried_item");
            byte tickEnd = (byte) fixture.id(PacketDirection.SERVERBOUND, "client_tick_end");
            fixture.serverbound("set_carried_item", (byte) 0, (byte) 3);
            fixture.tickEnd();
            assertEquals(List.of("forwarded " + held, "forwarded " + tickEnd, "marker 0"), order);
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void theConnectionKnowsWhenTheClientIsInsideItsTick(Host host) {
        Consumer<PacketRouteBuilder> moves = routes -> {
            routes.receiveConnection(
                    ServerboundPackets.MOVE_PLAYER, PacketListenerPriority.NORMAL, (event, packet) -> {});
            routes.receiveConnection(
                    ServerboundPackets.CLIENT_TICK_END, PacketListenerPriority.NORMAL, (event, packet) -> {});
        };
        try (CaptureFixture fixture = joined(host, moves)) {
            List<String> ran = new ArrayList<>();
            assertFalse(fixture.connection.midClientTick());
            fixture.serverbound("move_player_status_only", (byte) 1);
            assertTrue(fixture.connection.midClientTick());
            fixture.connection.afterClientTick(() -> ran.add("after tick end"));
            assertEquals(List.of(), ran);
            fixture.tickEnd();
            assertFalse(fixture.connection.midClientTick());
            assertEquals(List.of("after tick end"), ran);
            fixture.tickEnd();
            assertEquals(List.of("after tick end"), ran);
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aHeldSlotTheServerSetsBackRejectsTheChangeWithoutAMarker(Host host) {
        MarkingBinder binder = new MarkingBinder(true);
        try (CaptureFixture fixture = joined(host, HELD, binder)) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) 3);
            fixture.clientbound("set_held_slot", (byte) 0);
            fixture.tickEnd();
            fixture.tickEnd();

            assertEquals(List.of(), binder.marked);
            List<Outcome> outcomes = outcomes(fixture);
            assertEquals(1, outcomes.size());
            assertEquals(Outcome.Status.STATUS_REJECTED, outcomes.get(0).getStatus());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void aHeldChangeCultacCancelsIsDropped(Host host) {
        MarkingBinder binder = new MarkingBinder(true);
        try (CaptureFixture fixture = joined(host, ROUTES, binder)) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) 3);
            fixture.tickEnd();
            fixture.tickEnd();

            assertEquals(List.of(), binder.marked);
            List<Outcome> outcomes = outcomes(fixture);
            assertEquals(1, outcomes.size());
            assertEquals(Outcome.Type.TYPE_HELD, outcomes.get(0).getType());
            assertEquals(Outcome.Status.STATUS_DROPPED, outcomes.get(0).getStatus());
        }
    }

    @ParameterizedTest
    @EnumSource(Host.class)
    void withoutABackendThatAnswersMarkersHeldChangesGetNoOutcome(Host host) {
        MarkingBinder binder = new MarkingBinder(false);
        try (CaptureFixture fixture = joined(host, HELD.andThen(ROUTES), binder)) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) 3);
            fixture.tickEnd();
            CloudCapture.markerAnswered(fixture.player, 0);
            fixture.tickEnd();

            assertEquals(List.of(), binder.marked);
            assertEquals(List.of(), outcomes(fixture));
        }
    }

    private static List<Frame> frames(CaptureFixture fixture) {
        return fixture.uplinks.socket("a").of(IntakeMessage.KindCase.FRAME).stream()
                .map(IntakeMessage::getFrame)
                .toList();
    }

    private static List<Outcome> outcomes(CaptureFixture fixture) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Record record : records(fixture)) {
            if (record.getBodyCase() == Record.BodyCase.VERDICT)
                outcomes.addAll(record.getVerdict().getOutcomes().getOutcomesList());
        }
        return outcomes;
    }

    private static void drain(CaptureFixture fixture) {
        Object read;
        while ((read = fixture.channel.readInbound()) != null) ReferenceCountUtil.release(read);
    }

    private static void drainOutbound(CaptureFixture fixture) {
        Object written;
        while ((written = fixture.channel.readOutbound()) != null) ReferenceCountUtil.release(written);
    }

    private static List<Record> records(CaptureFixture fixture) {
        List<Record> records = new ArrayList<>();
        for (IntakeMessage message : fixture.uplinks.socket("a").of(IntakeMessage.KindCase.FRAME))
            records.addAll(message.getFrame().getRecordsList());
        return records;
    }

    private static List<byte[]> serverbound(CaptureFixture fixture) {
        List<byte[]> bytes = new ArrayList<>();
        for (Record record : records(fixture)) {
            if (record.getBodyCase() == Record.BodyCase.SERVERBOUND)
                bytes.add(record.getServerbound().toByteArray());
        }
        return bytes;
    }
}
