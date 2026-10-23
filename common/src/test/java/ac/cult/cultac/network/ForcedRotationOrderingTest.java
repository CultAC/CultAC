package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.aim.processor.AimProcessor;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsB;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.checks.impl.packetorder.PacketOrderO;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForcedRotationOrderingTest {
    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void relativeRotationUsesThePrecedingOrderedEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            fixture.player.xRot = fixture.player.yRot = 0;
            send(fixture, new ClientboundPlayerRotation(90, false, 30, false));
            send(fixture, new ClientboundPlayerRotation(45, true, 80, true));
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertTrue(teleports.checkRotationTeleportQueue(90, 30).isTeleport());
            fixture.player.xRot = 90;
            fixture.player.yRot = 30;
            var response = teleports.checkRotationTeleportQueue(135, 90);
            assertTrue(response.isTeleport(), "Vanilla applies each relative rotation at receipt, then clamps pitch");
            assertEquals(135, response.getTeleportData().getFinalYaw());
            assertEquals(90, response.getTeleportData().getFinalPitch());
            assertTrue(teleports.pendingTeleports.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void relativeRotationUsesMovementReportedBeforeItsEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            fixture.player.xRot = fixture.player.yRot = 0;
            send(fixture, new ClientboundPlayerRotation(45, true, -15, true));
            fixture.player.xRot = 30;
            fixture.player.yRot = 20;
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertFalse(teleports.checkRotationTeleportQueue(45, -15).isTeleport());
            assertEquals(1, teleports.pendingTeleports.size());
            var response = teleports.checkRotationTeleportQueue(75, 5);
            assertTrue(response.isTeleport());
            assertEquals(30, response.getTeleportData().getSourceYaw());
            assertEquals(20, response.getTeleportData().getSourcePitch());
        }
    }

    @ParameterizedTest
    @EnumSource(ProtocolVersion.class)
    void absoluteRotationStillRequiresItsExactTarget(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            register(fixture);
            send(fixture, new ClientboundPlayerRotation(90, false, 30, false));
            fixture.player.xRot = 45;
            fixture.player.yRot = 15;
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertFalse(teleports.checkRotationTeleportQueue(135, 45).isTeleport());
            assertEquals(1, teleports.pendingTeleports.size());
            assertTrue(teleports.checkRotationTeleportQueue(90, 30).isTeleport());
            assertTrue(teleports.pendingTeleports.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_9", "V1_21_11", "V26_1", "V26_2", "V26_3"})
    void aRotationInAFlushBundleIsProvenByAPingAfterItsEcho(ProtocolVersion version) {
        OfflineCultTestBootstrap.installConfig();
        try (var fixture = new RecordReceiveFixture(version)) {
            fixture.user.bind(mock(PlatformPlayer.class));
            register(fixture);
            var flushProof = new AtomicInteger();
            fixture.registrar.register(routes -> routes.send(
                    ClientboundPackets.ENTITY_MOTION,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> flushProof.set(player.appendFlushProofTransaction(event))));
            // A flush closes the flush bundle; the watchdog's would land between the writes.
            fixture.player.nettyScheduler.removeScheduler();
            fixture.channel.runPendingTasks();
            fixture.channel.write(new CultWrite(new ClientboundEntityMotion(1, new Vec3d(0, 0, 0)), false));
            fixture.channel.write(new CultWrite(new ClientboundPlayerRotation(90, false, 30, false), false));
            assertTrue(fixture.connection.flushBundleOpen());
            var teleports = fixture.player.getSetbackTeleportUtil();
            assertEquals(1, teleports.pendingTeleports.size(), "The rotation was handled");
            // ClientPacketListener#handleRotatePlayer sends Rot after the bundle's earlier ping is answered.
            assertTrue(flushProof.get() > 0, "The motion opens the flush bundle with a ping");
            int rotationProof = teleports.pendingTeleports.peek().getTransaction();
            assertTrue(rotationProof > flushProof.get(), "The rotation needs a ping after its echo");
            assertEquals(fixture.player.lastTransactionSent.get(), rotationProof);
            fixture.channel.flush();
            fixture.channel.releaseOutbound();
        }
    }

    @Test
    void aRelativeRotationEchoCarriesTheClientsTurnSinceItsLastTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            var rotation = echoAfterATurn(f);
            pong(f, rotation.getTransaction());
            var tick = new ServerboundMovePlayer(0, 0, 0, 3.5F, .75F, true, false, false, true);
            f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, tick), f.player, tick);
            f.tickEnd();
            assertTrue(f.player.getSetbackTeleportUtil().pendingTeleports.isEmpty());
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsB.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(PacketOrderO.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(TimerCheck.class).violations);
            assertEquals(0, f.player.checkManager.getCheck(TickTimer.class).violations);
        }
    }

    /** The echo reveals the client's own turn, which the rotation checks receive without the relative angle. */
    @Test
    void aRelativeRotationEchoGivesTheClientsTurnToTheRotationChecks() throws Exception {
        try (var f = new JavaTickFixture()) {
            echoAfterATurn(f);
            var aim = f.player.checkManager.getCheck(AimProcessor.class);
            assertEquals(2.5F, (float) field(aim, "lastXRot"), 1.0E-4F);
            assertEquals(.75F, (float) field(aim, "lastYRot"), 1.0E-4F);
        }
    }

    @Test
    void aClampedEchoTakesTheLivePitchNearestTheLastReported() {
        // A pitch of 80 or more clamps to 90 after 10 more; 85 is the nearest to the last report.
        assertEquals(85.0F, TeleportData.pitchBeforeRelativeRotation(90.0F, 10.0F, 85.0F));
        assertEquals(80.0F, TeleportData.pitchBeforeRelativeRotation(90.0F, 10.0F, 30.0F));
        assertEquals(-60.0F, TeleportData.pitchBeforeRelativeRotation(-90.0F, -30.0F, -60.0F));
        assertEquals(20.0F, TeleportData.pitchBeforeRelativeRotation(30.0F, 10.0F, 0.0F));
        assertEquals(60.0F, TeleportData.pitchBeforeRelativeRotation(-90.0F, -500.0F, 60.0F));
        assertEquals(-70.0F, TeleportData.pitchBeforeRelativeRotation(90.0F, 300.0F, -70.0F));
    }

    /**
     * After a standing tick at yaw and pitch 0, MouseHandler turns the client 2.5 right and 0.75 down, then
     * `/rotate @s ~1 ~` arrives and ClientPacketListener#handleRotatePlayer answers with its Rot echo.
     */
    private static TeleportData echoAfterATurn(JavaTickFixture f) {
        f.player.recordProvenance(true);
        f.player.nettyScheduler.removeScheduler();
        f.fixture.channel.runPendingTasks();
        register(f.fixture);
        Vec3 standing = new Vec3(.5, JavaTickFixture.FLOOR_Y, .5);
        f.place(standing, Vec3.ZERO);
        f.player.xRot = f.player.yRot = 0;
        f.tick(standing, true);
        pong(f, f.player.lastTransactionSent.get());
        f.fixture.channel.write(new CultWrite(new ClientboundPlayerRotation(1, true, 0, true), false));
        f.fixture.channel.flush();
        var rotation = f.player.getSetbackTeleportUtil().pendingTeleports.peek();
        assertNotNull(rotation);
        assertTrue(rotation.getBundleTransaction() < rotation.getTransaction());
        pong(f, rotation.getBundleTransaction());
        var echo = new ServerboundMovePlayer(0, 0, 0, 3.5F, .75F, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, echo), f.player, echo);
        assertTrue(f.player.getSetbackTeleportUtil().pendingTeleports.isEmpty(), "the Rot is the echo");
        return rotation;
    }

    /** The client answers each ping through {@code transaction} with a pong. */
    private static void pong(JavaTickFixture f, int transaction) {
        var pings = new PacketPingListener();
        for (int id = f.player.lastTransactionReceived.get() + 1; id <= transaction; id++) {
            Integer wireId = f.player.wireIdOf(id);
            assertNotNull(wireId, "ping " + id + " was sent");
            var pong = new ServerboundPong(wireId);
            var event = f.event(ServerboundPackets.PONG, pong);
            pings.onPong(event, f.player, pong);
            f.listener.onPong(event, f.player, pong);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void register(RecordReceiveFixture fixture) {
        var listener = new PacketServerTeleport();
        fixture.registrar.register(routes -> routes.send(
                ClientboundPackets.PLAYER_ROTATION, PacketListenerPriority.LOW, listener::onPlayerRotation));
    }

    private static void send(RecordReceiveFixture fixture, ClientboundPlayerRotation packet) {
        assertTrue(fixture.channel.writeOutbound(new CultWrite(packet, false)));
    }
}
