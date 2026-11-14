package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.cloud.Cloud;
import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.capture.CaptureStream;
import ac.cult.cultac.cloud.intake.v3.Flag;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.Verdict;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetHeldSlot;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Segments of backends in {@code cloud.uncaptured-servers}, through the events a proxy delivers. */
class CloudCaptureUncapturedTest {
    private final Binder binder = new Binder();

    /** Names a login's backend ahead of the bind as a proxy's connect event does, and binds when the test says so. */
    private static final class Binder implements SegmentBinder {
        final List<Consumer<String>> started = new ArrayList<>();
        String upcoming;
        int asked;
        int configuring;
        int marks;

        @Override
        public void started(CultPlayer player, Consumer<String> bind, boolean playing) {
            started.add(bind);
        }

        @Override
        public boolean marks(CultPlayer player) {
            marks++;
            return false;
        }

        @Override
        public String upcoming(CultPlayer player, boolean playing) {
            asked++;
            return upcoming;
        }

        @Override
        public void configuring(CultPlayer player) {
            configuring++;
        }

        void bind(int segment, String server) {
            started.get(segment).accept(server);
        }
    }

    /** A packet's bytes that count every byte read from them. */
    private static final class CountingBuf extends UnpooledHeapByteBuf {
        int reads;

        CountingBuf(byte[] bytes) {
            super(UnpooledByteBufAllocator.DEFAULT, bytes, bytes.length);
        }

        @Override
        public byte getByte(int index) {
            reads++;
            return super.getByte(index);
        }

        @Override
        public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
            reads++;
            return super.getBytes(index, dst, dstIndex, length);
        }

        @Override
        public long getLong(int index) {
            reads++;
            return super.getLong(index);
        }
    }

    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
        CloudCapture.uncaptured(UncapturedServers.NONE);
    }

    private CaptureFixture fixture(String... uncaptured) {
        CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.PROXY, false);
        CloudCapture.segmentBinder(binder);
        CloudCapture.uncaptured(UncapturedServers.of(List.of(uncaptured)));
        fixture.phase(ConnectionPhase.CONFIGURATION);
        fixture.install();
        fixture.phase(ConnectionPhase.PLAY);
        return fixture;
    }

    /** The login of backend {@code server}, which the proxy named before forwarding it. */
    private void login(CaptureFixture fixture, String server, int entityId) {
        binder.upcoming = server;
        fixture.capture.loginSent(new ClientboundLogin(
                entityId, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL)));
        fixture.clientbound("login", (byte) 1);
    }

    private void startConfiguration(CaptureFixture fixture) {
        fixture.capture.configurationStarted();
        fixture.clientbound("start_configuration");
        fixture.phase(ConnectionPhase.CONFIGURATION);
        binder.upcoming = null;
        fixture.phase(ConnectionPhase.PLAY);
    }

    private static void ticks(CaptureFixture fixture, int first, int ticks) {
        for (int i = 0; i < ticks; i++) {
            fixture.serverbound("set_carried_item", (byte) 0, (byte) (first + i));
            fixture.tickEnd();
        }
    }

    /** Bytes of the capture's reads of one packet each way, a movement and an entity move. */
    private static int reads(CaptureFixture fixture) {
        CountingBuf in = counting(fixture, PacketDirection.SERVERBOUND, "move_player_pos", 32);
        fixture.channel.writeInbound(in);
        Object read;
        while ((read = fixture.channel.readInbound()) != null) ReferenceCountUtil.release(read);
        CountingBuf out = counting(fixture, PacketDirection.CLIENTBOUND, "move_entity_pos", 12);
        fixture.channel.writeOutbound(out);
        Object written;
        while ((written = fixture.channel.readOutbound()) != null) ReferenceCountUtil.release(written);
        return in.reads + out.reads;
    }

    private static CountingBuf counting(CaptureFixture fixture, PacketDirection direction, String name, int body) {
        ByteBuf bytes = Unpooled.buffer();
        Wire.writeVarInt(bytes, fixture.id(direction, name));
        bytes.writeZero(body);
        byte[] array = new byte[bytes.readableBytes()];
        bytes.readBytes(array);
        bytes.release();
        CountingBuf counting = new CountingBuf(array);
        counting.writerIndex(array.length);
        return counting;
    }

    @Test
    void aFirstLoginIntoAnUncapturedBackendCapturesAndSchedulesNothing() {
        try (CaptureFixture fixture = fixture("hub-*")) {
            fixture.channel.freezeTime();
            login(fixture, "Hub-1", 7);
            ticks(fixture, 0, 3);
            assertEquals(0, reads(fixture), "no tap reads a packet of an uncaptured backend");
            assertTrue(binder.started.isEmpty(), "no bind is asked for");
            long discarded = CloudCapture.unbound.get();
            fixture.channel.advanceTimeBy(CloudCapture.BIND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            fixture.channel.runScheduledPendingTasks();
            assertEquals(discarded, CloudCapture.unbound.get(), "no bind timeout runs");
            assertEquals(0, fixture.uplinks.opened);
            assertFalse(fixture.capture.streaming());
        }
    }

    @Test
    void leavingAnUncapturedBackendJoinsTheNextAtItsLogin() {
        try (CaptureFixture fixture = fixture("hub")) {
            login(fixture, "hub", 7);
            ticks(fixture, 0, 2);
            startConfiguration(fixture);
            assertEquals(1, binder.configuring);
            login(fixture, "game", 9);
            assertTrue(reads(fixture) > 0, "the taps read a captured backend's packets");
            binder.bind(0, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 2, 2);

            assertEquals(1, fixture.uplinks.opened);
            assertEquals(
                    List.of(
                            IntakeMessage.KindCase.JOIN, IntakeMessage.KindCase.FRAME, IntakeMessage.KindCase.FRAME),
                    game.kinds());
            var join = game.of(IntakeMessage.KindCase.JOIN).get(0).getJoin();
            assertEquals(9, join.getEntityId());
            assertFalse(join.getResumed());
            assertEquals(List.of(0L, 1L), seqs(game));
            assertEquals(List.of(2, 3), held(fixture, game));
            assertEquals(
                    fixture.id(PacketDirection.CLIENTBOUND, "login"),
                    firstClientbound(game.of(IntakeMessage.KindCase.FRAME).get(0)));
        }
    }

    @Test
    void enteringAnUncapturedBackendLeavesAtTheStartConfiguration() {
        try (CaptureFixture fixture = fixture("hub")) {
            login(fixture, "game", 7);
            binder.bind(0, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 2);
            startConfiguration(fixture);
            login(fixture, "HUB", 9);
            ticks(fixture, 2, 3);

            assertEquals(0, reads(fixture));
            assertEquals(
                    List.of(
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.LEAVE),
                    game.kinds());
            assertEquals(List.of(0, 1), held(fixture, game));
            assertEquals(1, binder.started.size());
            assertEquals(1, fixture.uplinks.released);
        }
    }

    @Test
    void movingBetweenUncapturedBackendsCapturesNothing() {
        try (CaptureFixture fixture = fixture("hub", "wait-*")) {
            login(fixture, "hub", 7);
            ticks(fixture, 0, 1);
            startConfiguration(fixture);
            login(fixture, "wait-2", 8);
            ticks(fixture, 1, 1);
            assertEquals(0, reads(fixture));
            startConfiguration(fixture);
            assertEquals(2, binder.configuring);
            assertTrue(binder.started.isEmpty());
            assertEquals(0, fixture.uplinks.opened);
        }
    }

    @Test
    void aSwitchThatFallsBackLogsInFromTheBackendNamedLast() {
        try (CaptureFixture fixture = fixture("hub")) {
            login(fixture, "game", 7);
            binder.bind(0, "game");
            fixture.channel.runPendingTasks();
            fixture.uplinks.socket("game").captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            startConfiguration(fixture);
            binder.upcoming = "hub";
            login(fixture, "game", 7);
            binder.bind(1, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 1, 1);
            assertEquals(
                    List.of(
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME,
                            IntakeMessage.KindCase.LEAVE,
                            IntakeMessage.KindCase.JOIN,
                            IntakeMessage.KindCase.FRAME),
                    game.kinds());
        }
    }

    @Test
    void aBindToAnUncapturedBackendDropsWhatTheSegmentHeldUnsent() {
        try (CaptureFixture fixture = fixture("hub")) {
            fixture.channel.freezeTime();
            long discarded = CloudCapture.unbound.get();
            login(fixture, null, 7);
            ticks(fixture, 0, 2);
            binder.bind(0, "hub");
            fixture.channel.runPendingTasks();
            ticks(fixture, 2, 2);
            assertEquals(0, reads(fixture));
            assertEquals(0, fixture.uplinks.opened);
            fixture.channel.advanceTimeBy(CloudCapture.BIND_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
            fixture.channel.runScheduledPendingTasks();
            assertEquals(discarded, CloudCapture.unbound.get(), "a bound segment is never discarded unbound");

            startConfiguration(fixture);
            login(fixture, "game", 9);
            binder.bind(1, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 4, 1);
            assertEquals(List.of(IntakeMessage.KindCase.JOIN, IntakeMessage.KindCase.FRAME), game.kinds());
            assertEquals(List.of(4), held(fixture, game));
        }
    }

    @Test
    void aReloadLeavesABackendItAddsAndResumesOneItRemoves() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, "game", 7);
            binder.bind(0, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);

            CloudCapture.uncaptured(UncapturedServers.of(List.of("GAME")));
            fixture.install();
            assertEquals(IntakeMessage.KindCase.LEAVE, game.kinds().get(game.kinds().size() - 1));
            ticks(fixture, 1, 2);
            assertEquals(0, reads(fixture));
            int sent = game.sent.size();

            CloudCapture.uncaptured(UncapturedServers.NONE);
            binder.upcoming = "game";
            fixture.install();
            assertEquals(2, binder.started.size());
            binder.bind(1, "game");
            fixture.channel.runPendingTasks();
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 3, 1);
            List<IntakeMessage> resumed = game.sent.subList(sent, game.sent.size());
            assertEquals(IntakeMessage.KindCase.JOIN, resumed.get(0).getKindCase());
            assertTrue(resumed.get(0).getJoin().getResumed());
            assertEquals(7, resumed.get(0).getJoin().getEntityId());
            assertEquals(List.of(0, 3), held(fixture, game));
        }
    }

    @Test
    void anEmptyListNeverAsksTheBinderAhead() {
        try (CaptureFixture fixture = fixture()) {
            login(fixture, "hub", 7);
            binder.bind(0, "hub");
            fixture.channel.runPendingTasks();
            fixture.uplinks.socket("hub").captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            startConfiguration(fixture);
            login(fixture, "hub", 7);
            assertEquals(0, binder.asked);
            assertEquals(2, binder.started.size());
        }
    }

    @Test
    void onAnUncapturedBackendChecksAndFindingsStillFlagWhileNoVerdictCarriesThem() throws Exception {
        try (CaptureFixture fixture = fixture("hub")) {
            login(fixture, "game", 7);
            binder.bind(0, "game");
            fixture.channel.runPendingTasks();
            FakeUplink game = fixture.uplinks.socket("game");
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 0, 1);
            startConfiguration(fixture);
            login(fixture, "hub", 9);
            assertFalse(
                    game.streams.containsKey(fixture.player.getUniqueId()),
                    "the player leaves the socket as at any switch, which keeps taking their findings");
            ticks(fixture, 1, 1);

            CloudCaptureIsolationTest.Nameless local = new CloudCaptureIsolationTest.Nameless(fixture.player);
            assertTrue(local.flag("on the hub"));
            assertEquals(1, local.violations, "a check flags, alerts and punishes as anywhere");
            Cloud cloud = fixture.player.checkManager.getCheck(Cloud.class);
            assertNotNull(cloud);
            double before = cloud.violations;
            Field dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
            dataStore.setAccessible(true);
            Object previous = dataStore.get(CultAPI.INSTANCE);
            dataStore.set(
                    CultAPI.INSTANCE,
                    new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
            try {
                cloud.finding("aim", 0.9, "earlier play");
            } finally {
                dataStore.set(CultAPI.INSTANCE, previous);
            }
            assertTrue(cloud.violations > before, "a finding the service sends still flags");

            startConfiguration(fixture);
            login(fixture, "game", 11);
            binder.bind(1, "game");
            fixture.channel.runPendingTasks();
            game.captureAll(fixture.player.getUniqueId());
            ticks(fixture, 2, 1);
            List<Verdict> verdicts = verdicts(game);
            assertFalse(verdicts.isEmpty());
            for (Verdict verdict : verdicts) {
                for (Flag flag : verdict.getFlagsList()) {
                    assertNotEquals("Nameless", flag.getCheck(), "a flag raised on the hub reaches no verdict");
                }
            }
        }
    }

    @Test
    void onAnUncapturedBackendTheActionObserversDoNothing() throws Exception {
        try (CaptureFixture fixture = fixture("hub")) {
            login(fixture, "hub", 7);
            ticks(fixture, 0, 1);
            long dropped = CaptureStream.outcomesDropped.get();

            fixture.capture.received(new ServerboundSetCarriedItem(3), false);
            assertEquals(0, binder.marks, "a held-slot change is not followed");

            fixture.capture.totemPopped();
            assertEquals(dropped, CaptureStream.outcomesDropped.get(), "a totem pop gets no outcome");

            Field field = CloudCapture.class.getDeclaredField("actions");
            field.setAccessible(true);
            ((ActionOutcomes) field.get(fixture.capture)).held(null, 2, false, false, 0);
            fixture.capture.sent(new ClientboundSetHeldSlot(5));
            assertEquals(dropped, CaptureStream.outcomesDropped.get(), "a clientbound packet settles nothing");
        }
    }

    private static List<Verdict> verdicts(FakeUplink socket) {
        List<Verdict> verdicts = new ArrayList<>();
        for (IntakeMessage message : socket.of(IntakeMessage.KindCase.FRAME)) {
            for (Record record : message.getFrame().getRecordsList()) {
                if (record.getBodyCase() == Record.BodyCase.VERDICT) verdicts.add(record.getVerdict());
            }
        }
        return verdicts;
    }

    private static List<Long> seqs(FakeUplink socket) {
        List<Long> seqs = new ArrayList<>();
        for (IntakeMessage message : socket.of(IntakeMessage.KindCase.FRAME))
            seqs.add(message.getFrame().getSeq());
        return seqs;
    }

    /** The held-slot changes the frames on {@code socket} carry, by the number each was sent with. */
    private static List<Integer> held(CaptureFixture fixture, FakeUplink socket) {
        int slot = fixture.id(PacketDirection.SERVERBOUND, "set_carried_item");
        List<Integer> held = new ArrayList<>();
        for (IntakeMessage message : socket.of(IntakeMessage.KindCase.FRAME)) {
            for (Record record : message.getFrame().getRecordsList()) {
                if (record.getBodyCase() != Record.BodyCase.SERVERBOUND) continue;
                byte[] bytes = record.getServerbound().toByteArray();
                if (bytes[0] == slot) held.add((int) bytes[2]);
            }
        }
        return held;
    }

    private static int firstClientbound(IntakeMessage frame) {
        for (Record record : frame.getFrame().getRecordsList()) {
            if (record.getBodyCase() == Record.BodyCase.CLIENTBOUND)
                return record.getClientbound().byteAt(0);
        }
        return -1;
    }
}
