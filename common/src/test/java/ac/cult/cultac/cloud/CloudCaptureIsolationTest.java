package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.combat.PlayerReach;
import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.CaptureFixture.Host;
import ac.cult.cultac.cloud.diag.Cut;
import ac.cult.cultac.cloud.diag.Limits;
import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.Flag;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.cloud.intake.v3.Verdict;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import com.google.protobuf.InvalidProtocolBufferException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** What the capture adds to a player's checks never stops them, and costs nothing while the upload is off. */
class CloudCaptureIsolationTest {
    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    /** A player already in play when the capture installs, joined on socket {@code a}, every packet wanted. */
    private static CaptureFixture joined(Host host) {
        CaptureFixture fixture = new CaptureFixture(host, false);
        CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
        fixture.install();
        fixture.uplinks.socket("a").captureAll(fixture.player.getUniqueId());
        return fixture;
    }

    @Test
    void aThrowingEngineLeavesTheVerdictMissing() throws Exception {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            Object entities = get(fixture.player, "compensatedEntities");
            set(fixture.player, "compensatedEntities", null);
            try {
                assertDoesNotThrow(fixture::tickEnd, "the engine's read throws");
            } finally {
                set(fixture.player, "compensatedEntities", entities);
            }
            fixture.tickEnd();

            List<Verdict> verdicts = verdicts(fixture.uplinks.socket("a"));
            assertEquals(2, verdicts.size());
            assertTrue(verdicts.get(0).getMissing());
            assertFalse(verdicts.get(1).getMissing());
        }
    }

    @Test
    void aSendHandlerThatThrowsReturnsNormally() {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            CloudCapture capture = fixture.capture;
            assertDoesNotThrow(() -> capture.loginSent(null));
            assertDoesNotThrow(() -> capture.respawnSent(null));
            assertDoesNotThrow(() -> capture.gameModeSent(null));
            fixture.tickEnd();
            assertEquals(1, verdicts(fixture.uplinks.socket("a")).size(), "the segment keeps uploading");
        }
    }

    @Test
    void anAttackGoesOutWithItsGeometry() throws Exception {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            PlayerReach reach = attacked(fixture);
            assertTrue(fixture.capture.streaming());

            double before = reach.violations;
            reach.onPlayerTickEnd(null);
            assertTrue(reach.violations > before);
            fixture.tickEnd();
            List<Verdict> verdicts = verdicts(fixture.uplinks.socket("a"));
            Verdict verdict = verdicts.get(verdicts.size() - 1);
            assertEquals(1, verdict.getGeometryCount());
            assertEquals(0, verdict.getGeometry(0).getAttack());
            assertEquals("minecraft:player", verdict.getGeometry(0).getType());
            assertTrue(verdict.getGeometry(0).hasReach());
            assertEquals(100, verdict.getTarget().getEntityId());
        }
    }

    @Test
    void aFlagRaisedAtATickEndIsInThatTicksVerdict() throws Exception {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            PlayerReach reach = attacked(fixture);
            fixture.serverbound("client_tick_end");
            fixture.player.clientTickEnds.incrementAndGet();
            fixture.player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    fixture.user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.CLIENT_TICK_END,
                    ServerboundPackets.CLIENT_TICK_END.opaqueValue()));
            fixture.channel.runPendingTasks();
            assertTrue(reach.violations > 0);
            List<Verdict> verdicts = verdicts(fixture.uplinks.socket("a"));
            assertEquals(
                    List.of(Flag.newBuilder()
                            .setCheck("PlayerReach")
                            .setViolationsHundredths((int) Math.round(reach.violations * 100))
                            .build()),
                    verdicts.get(verdicts.size() - 1).getFlagsList());
        }
    }

    @Test
    void aCheckWithoutANameFlagsUnderItsClassName() {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            Nameless check = new Nameless(fixture.player);
            assertNull(check.getCheckName());
            fixture.capture.flagged(check, 1);
            fixture.tickEnd();
            List<Verdict> verdicts = verdicts(fixture.uplinks.socket("a"));
            assertEquals(
                    "Nameless", verdicts.get(verdicts.size() - 1).getFlags(0).getCheck());
        }
    }

    @Test
    void aPredictionDuringTickEndBelongsToTheCutFrame() throws Exception {
        try (CaptureFixture fixture = joined(Host.SERVER)) {
            fixture.tickEnd();
            fixture.tickEnd();
            Method frameSeq = CloudCapture.class.getDeclaredMethod("frameSeq");
            frameSeq.setAccessible(true);
            long open = (long) frameSeq.invoke(fixture.capture);
            set(fixture.player.checkManager, "dispatchingTickEnd", true);
            try {
                assertEquals(open - 1, (long) frameSeq.invoke(fixture.capture));
            } finally {
                set(fixture.player.checkManager, "dispatchingTickEnd", false);
            }
            assertEquals(2, open, "two frames were cut, so the open one is the third");
        }
    }

    @Test
    void aBedrockPlayersDiagnosticsCutIsAnsweredWithNoCoverage() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            fixture.player.movementPlatform = MovementPlatform.BEDROCK;
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            FakeUplink a = fixture.uplinks.socket("a");
            a.diagnostics.connected(1, Limits.of(true, 0, 0, 0, 0, 0, 0, 0, 0));
            byte[] bundle = new byte[16];
            a.reply(
                    fixture.player.getUniqueId(),
                    new IntakeWire.DiagCutReply(fixture.player.getUniqueId(), new Cut(bundle, 0, 1, 0, 1)));
            fixture.channel.runPendingTasks();
            byte[] record = a.upload.poll(1, System.nanoTime() + TimeUnit.HOURS.toNanos(1));
            assertNotNull(record, "the cut is answered");
            DiagPart part = part(record);
            assertEquals(DiagCoverage.DIAG_COVERAGE_NONE, part.getCoverage());
            assertEquals("bedrock", part.getReason());
            assertTrue(part.getLast());
        }
    }

    @Test
    void aCaptureWhileTheUploadIsOffHoldsNoStream() throws Exception {
        try (CaptureFixture fixture = new CaptureFixture(Host.SERVER, false)) {
            assertNull(get(fixture.capture, "stream"));
            fixture.uplinks.enabled = false;
            fixture.install();
            assertNull(get(fixture.capture, "stream"));
            assertNull(get(fixture.capture, "engine"));
            assertNull(fixture.channel.pipeline().get(CloudCapture.INBOUND));
            fixture.uplinks.enabled = true;
            fixture.install();
            assertNotNull(get(fixture.capture, "stream"));
            assertNotNull(get(fixture.capture, "engine"));
        }
    }

    /** {@code fixture}'s player, every check but PlayerReach disabled, attacks a player 3.8 blocks away. */
    private static PlayerReach attacked(CaptureFixture fixture) {
        CultPlayer player = fixture.player;
        PlatformWorld platformWorld = Mockito.mock(PlatformWorld.class);
        Mockito.when(platformWorld.getName()).thenReturn("world");
        Mockito.when(player.platformPlayer.getWorld()).thenReturn(platformWorld);
        player.checkManager.allChecks.values().forEach(check -> check.setEnabled(false));
        PlayerReach reach = player.checkManager.getCheck(PlayerReach.class);
        reach.setEnabled(true);
        player.gamemode = GameMode.SURVIVAL;
        player.x = .5;
        player.y = 64;
        player.z = -1.5;
        player.registryState = new ClientComponentRegistries();
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        var world = player.compensatedWorld;
        var dimension = player.getWorldRegistries().dimension("minecraft:overworld");
        world.setLastClientboundDimension("minecraft:overworld", dimension.dimension());
        world.setDimension("minecraft:overworld", dimension);
        world.ensureValidationChunkLoaded(0, 0);
        world.ensureValidationChunkLoaded(0, -1);
        player.compensatedEntities.addEntity(100, EntityTypeIds.PLAYER, new Vec3(.5, 64, 2.3), 0, 0, 0);
        fixture.tickEnd();
        player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                fixture.user,
                ConnectionPhase.PLAY,
                ServerboundPackets.INTERACT,
                new ServerboundInteract(100, InteractAction.ATTACK, Hand.MAIN_HAND, Optional.empty(), false)));
        return reach;
    }

    /** A check that declares no name. */
    static final class Nameless extends Check {
        Nameless(CultPlayer player) {
            super(player);
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

    private static DiagPart part(byte[] record) throws InvalidProtocolBufferException {
        return Intake.parseFrom(record).getMessages(0).getDiag();
    }

    private static Object get(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
