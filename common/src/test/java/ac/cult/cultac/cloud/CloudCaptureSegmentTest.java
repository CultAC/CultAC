package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.intake.v3.Frame;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Join;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.World;
import ac.cult.cultac.cloud.transport.CloudConnection;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CloudCaptureSegmentTest {
    private final Binder binder = new Binder();

    /** Names each segment's backend only when the test says so, as a proxy's connection events do. */
    private static final class Binder implements SegmentBinder {
        final List<Consumer<String>> started = new ArrayList<>();
        int configuring;
        int disconnected;

        @Override
        public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
            started.add(bind);
        }

        @Override
        public void configuring(CultPlayer player) {
            configuring++;
        }

        @Override
        public void disconnected(CultPlayer player) {
            disconnected++;
        }

        void bind(int segment, String server) {
            started.get(segment).accept(server);
        }
    }

    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    private CaptureFixture fixture() {
        CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.PROXY, false);
        CloudCapture.segmentBinder(binder);
        fixture.phase(ConnectionPhase.CONFIGURATION);
        fixture.install();
        fixture.phase(ConnectionPhase.PLAY);
        return fixture;
    }

    /** The backend's login, as cultac sends it: its handlers first, then the bytes past the taps. */
    private static void login(CaptureFixture fixture, int entityId) {
        fixture.capture.loginSent(new ClientboundLogin(
                entityId, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL)));
        fixture.clientbound("login", (byte) 1);
    }

    private static void startConfiguration(CaptureFixture fixture) {
        fixture.capture.configurationStarted();
        fixture.clientbound("start_configuration");
        fixture.phase(ConnectionPhase.CONFIGURATION);
    }

    /** {@code ticks} client ticks, each a held-slot change numbered by {@code first} on, then the tick end. */
    private static void ticks(CaptureFixture fixture, int first, int ticks) {
        for (int i = 0; i < ticks; i++) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) (first + i));
            fixture.tickEnd();
        }
    }

    @Test
    void switchLeavesTheOldSocketAndJoinsTheNewOneWithFreshFrames() {
        try (CaptureFixture fixture = fixture()) {
            assertNotNull(fixture.channel.pipeline().get(CloudCapture.INBOUND));
            List<String> names = fixture.channel.pipeline().names();
            assertEquals(names.indexOf("via-decoder") - 1, names.indexOf(CloudCapture.INBOUND));
            assertEquals(names.indexOf("via-encoder") - 1, names.indexOf(CloudCapture.OUTBOUND));

            login(fixture, 7);
            assertEquals(1, binder.started.size());
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            FakeUplink a = fixture.uplinks.socket("a");
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 3);

            startConfiguration(fixture);
            assertEquals(1, binder.configuring);
            fixture.phase(ConnectionPhase.PLAY);
            login(fixture, 9);
            assertEquals(2, binder.started.size());
            binder.bind(1, "b");
            fixture.channel.runPendingTasks();
            FakeUplink b = fixture.uplinks.socket("b");
            b.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 3, 2);

            assertEquals(
                    List.of(
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.LEAVE),
                    a.kinds());
            assertEquals(
                    List.of(IntakeMessage.KindCase.JOIN, IntakeMessage.KindCase.FRAME, IntakeMessage.KindCase.FRAME),
                    b.kinds());
            assertEquals(List.of(0L, 1L, 2L), seqs(a));
            assertEquals(List.of(0L, 1L), seqs(b));
            assertEquals(7, a.of(IntakeMessage.KindCase.JOIN).get(0).getJoin().getEntityId());
            assertEquals(9, b.of(IntakeMessage.KindCase.JOIN).get(0).getJoin().getEntityId());
            assertEquals(List.of(0, 1, 2), swings(fixture, a));
            assertEquals(List.of(3, 4), swings(fixture, b));
            assertTrue(firstClientbound(fixture, a, "login"));
            assertTrue(firstClientbound(fixture, b, "login"));
            assertEquals(1, fixture.uplinks.released);
        }
    }

    @Test
    void framesBeforeALateBindAreHeldAndSentInOrder() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, 7);
            ticks(fixture, 0, 2);
            assertTrue(fixture.uplinks.sockets.isEmpty());
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            FakeUplink a = fixture.uplinks.socket("a");
            assertEquals(List.of(IntakeMessage.KindCase.JOIN), a.kinds());
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 2, 1);
            assertEquals(List.of(0L, 1L, 2L), seqs(a));
            assertEquals(List.of(0, 1, 2), swings(fixture, a));
        }
    }

    @Test
    void aReconfigurationOnTheSameBackendSplitsTheSessionOnItsSocket() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, 7);
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            FakeUplink a = fixture.uplinks.socket("a");
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            startConfiguration(fixture);
            fixture.phase(ConnectionPhase.PLAY);
            login(fixture, 7);
            binder.bind(1, "a");
            fixture.channel.runPendingTasks();
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 1, 1);
            assertEquals(
                    List.of(
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.LEAVE,
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME),
                    a.kinds());
            assertEquals(List.of(0L, 0L), seqs(a));
            assertEquals(List.of(0, 1), swings(fixture, a));
        }
    }

    @Test
    void aSegmentNoBackendClaimsIsDiscardedWithNothingSent() {
        try (CaptureFixture fixture = fixture()) {
            fixture.channel.freezeTime();
            long discarded = CloudCapture.unbound.get();
            login(fixture, 7);
            ticks(fixture, 0, 2);
            fixture.channel.advanceTimeBy(CloudCapture.BIND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            fixture.channel.runScheduledPendingTasks();
            assertEquals(discarded + 1, CloudCapture.unbound.get());
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            assertTrue(fixture.uplinks.sockets.isEmpty());
            assertEquals(0, fixture.uplinks.opened);
        }
    }

    @Test
    void aReloadResumesASegmentWhoseBindFailed() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, 7);
            binder.bind(0, " ");
            fixture.channel.runPendingTasks();
            assertTrue(fixture.uplinks.sockets.isEmpty());

            fixture.install();
            assertEquals(2, binder.started.size());
            binder.bind(1, "a");
            fixture.channel.runPendingTasks();
            FakeUplink a = fixture.uplinks.socket("a");
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            assertEquals(List.of(IntakeMessage.KindCase.JOIN, IntakeMessage.KindCase.FRAME), a.kinds());
            assertEquals(7, a.of(IntakeMessage.KindCase.JOIN).get(0).getJoin().getEntityId());
        }
    }

    @Test
    void disconnectEndsTheSegmentAndDropsAPendingBind() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, 7);
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            FakeUplink a = fixture.uplinks.socket("a");
            a.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            fixture.channel.close();
            fixture.channel.runPendingTasks();
            assertEquals(1, binder.disconnected);
            assertEquals(IntakeMessage.KindCase.LEAVE, a.kinds().get(a.kinds().size() - 1));
            assertTrue(a.streams.isEmpty());
        }
    }

    @Test
    void protocolFiveJoinsNameTheRegistries() {
        try (CaptureFixture fixture = fixture()) {
            fixture.receivedRegistries(
                    ProtocolVersion.V26_3,
                    Map.of("minecraft:damage_type", List.of("minecraft:arrow", "minecraft:magic")));
            fixture.uplinks.terms = CloudConnection.Terms.sections(2, 2);
            login(fixture, 7);
            binder.bind(0, "a");
            fixture.channel.runPendingTasks();
            Join join = fixture.uplinks
                    .socket("a")
                    .of(IntakeMessage.KindCase.JOIN)
                    .get(0)
                    .getJoin();
            assertEquals(777, join.getServerProtocol());
            assertEquals("true", join.getSettingsMap().get("send-check-flags"));
            assertEquals(
                    List.of("minecraft:arrow", "minecraft:magic"),
                    join.getRegistriesMap().get("minecraft:damage_type").getEntriesList());
        }
    }

    private static List<Long> seqs(FakeUplink socket) {
        List<Long> seqs = new ArrayList<>();
        for (IntakeMessage message : socket.of(IntakeMessage.KindCase.FRAME))
            seqs.add(message.getFrame().getSeq());
        return seqs;
    }

    /** The held-slot changes the frames on {@code socket} carry, by the number each was sent with. */
    private static List<Integer> swings(CaptureFixture fixture, FakeUplink socket) {
        int swing = fixture.id(PacketDirection.SERVERBOUND, "set_carried_item");
        List<Integer> swings = new ArrayList<>();
        for (IntakeMessage message : socket.of(IntakeMessage.KindCase.FRAME)) {
            for (Record record : message.getFrame().getRecordsList()) {
                if (record.getBodyCase() != Record.BodyCase.SERVERBOUND) continue;
                byte[] bytes = record.getServerbound().toByteArray();
                if (bytes[0] == swing) swings.add((int) bytes[2]);
            }
        }
        return swings;
    }

    @Test
    void firstClientboundLooksPastTheWorldToThePacket() {
        Record world = Record.newBuilder().setWorld(World.getDefaultInstance()).build();
        Record login = clientbound(5);
        Record other = clientbound(6);
        assertTrue(firstClientboundIs(
                Frame.newBuilder().addRecords(world).addRecords(login).build(), 5));
        assertFalse(firstClientboundIs(Frame.newBuilder().addRecords(world).build(), 5));
        assertFalse(firstClientboundIs(
                Frame.newBuilder().addRecords(world).addRecords(other).build(), 5));
    }

    private static Record clientbound(int id) {
        return Record.newBuilder()
                .setClientbound(ByteString.copyFrom(new byte[] {(byte) id, 0}))
                .build();
    }

    private static boolean firstClientbound(CaptureFixture fixture, FakeUplink socket, String name) {
        return firstClientboundIs(
                socket.of(IntakeMessage.KindCase.FRAME).get(0).getFrame(),
                fixture.id(PacketDirection.CLIENTBOUND, name));
    }

    /** Whether the frame's first clientbound packet, past any WORLD record, has packet id {@code id}. */
    private static boolean firstClientboundIs(Frame frame, int id) {
        for (Record record : frame.getRecordsList()) {
            if (record.getBodyCase() == Record.BodyCase.WORLD) continue;
            if (record.getBodyCase() == Record.BodyCase.CLIENTBOUND)
                return record.getClientbound().byteAt(0) == id;
        }
        return false;
    }
}
