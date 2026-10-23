package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsE;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TickTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaSetbackPacketOrderingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMismatchedAcceptTeleportationReachesTheServerOnlyInObserveOnly(boolean observeOnly) throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3);
                var ignored = RecordReceiveFixture.observeOnly(observeOnly)) {
            setup.fixture.registrar.register(routes -> routes.receive(
                    ServerboundPackets.ACCEPT_TELEPORTATION,
                    PacketListenerPriority.NORMAL,
                    new PacketServerTeleport()::onAcceptTeleportation));
            var teleports = setup.player.getSetbackTeleportUtil();
            teleports.addSentTeleport(new Vec3(10.5, 64, 10.5), 20, new RelativeFlag(0), true, 7);
            setup.player.lastTransactionReceived.set(20);

            var ack = setup.fixture.id(ServerboundPackets.ACCEPT_TELEPORTATION, "accept_teleportation");
            Wire.writeVarInt(ack, 7);
            ack.writeDouble(.5).writeDouble(64).writeDouble(.5).writeFloat(0).writeFloat(0);
            String bytes = ByteBufUtil.hexDump(ack);
            setup.fixture.channel.writeInbound(ack);
            ByteBuf forwarded = setup.fixture.channel.readInbound();
            if (observeOnly) {
                assertEquals(bytes, ByteBufUtil.hexDump(forwarded));
                forwarded.release();
                // The teleport stays pending until its proof transaction, then the next position releases it.
                assertFalse(teleports.pendingTeleports.isEmpty());
                setup.player.lastTransactionReceived.set(21);
                teleports.checkTeleportQueue(.5, 64, .5);
                assertTrue(teleports.pendingTeleports.isEmpty());
                assertFalse(teleports.isPendingSetback());
            } else {
                assertNull(forwarded);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V26_2", "V26_3"})
    void aProvenTeleportResponseCompletesTheSetbackWithoutBeingBlocked(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var teleports = setup.player.getSetbackTeleportUtil();
            var target = new Vec3(2, 64, 2);
            teleports.addSentTeleport(target, 20, new RelativeFlag(0), false, 1);
            // The immediate echo precedes the teleport's trailing proof transaction.
            setup.player.lastTransactionReceived.set(20);
            assertTrue(teleports.isPendingSetback());
            if (version == ProtocolVersion.V26_2) {
                var packet = new ServerboundMovePlayer(2, 64, 2, 0, 0, false, false, true, true);
                var event = new PacketReceiveEvent<>(
                        setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet);
                setup.listener.onMovePlayer(event, setup.player, packet);
                assertFalse(event.isCancelled());
            } else {
                var packet = new ServerboundAcceptTeleportation(1, new Vec3d(2, 64, 2), 0, 0);
                var event = new PacketReceiveEvent<>(
                        setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.ACCEPT_TELEPORTATION, packet);
                new PacketServerTeleport().onAcceptTeleportation(event, setup.player, packet);
                assertFalse(event.isCancelled());
            }
            assertFalse(teleports.isPendingSetback());
            assertEquals(2, setup.player.x, 1.0E-12);
            assertEquals(2, setup.player.z, 1.0E-12);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V26_2", "V26_3"})
    void everyRelativeTeleportOfABurstSentBeforeTheFirstResponseIsAccepted(ProtocolVersion version)
            throws Exception {
        try (var setup = new Setup(version)) {
            var teleports = setup.player.getSetbackTeleportUtil();
            int burst = 65;
            // Each X += 1 teleport follows its own leading ping; all are sent before the client answers the first.
            for (int id = 1; id <= burst; id++) {
                teleports.addSentTeleport(new Vec3(1, 64, .5), 20 + 2 * id, RelativeFlag.X, true, id);
            }

            for (int id = 1; id <= burst; id++) {
                setup.player.lastTransactionReceived.set(20 + 2 * id);
                double x = .5 + id;
                if (version == ProtocolVersion.V26_2) {
                    var ack = new ServerboundAcceptTeleportation(id, null, 0, 0);
                    var ackEvent = new PacketReceiveEvent<>(
                            setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.ACCEPT_TELEPORTATION, ack);
                    new PacketServerTeleport().onAcceptTeleportation(ackEvent, setup.player, ack);
                    var packet = new ServerboundMovePlayer(x, 64, .5, 0, 0, false, false, true, true);
                    var event = new PacketReceiveEvent<>(
                            setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet);
                    setup.listener.onMovePlayer(event, setup.player, packet);
                } else {
                    var packet = new ServerboundAcceptTeleportation(id, new Vec3d(x, 64, .5), 0, 0);
                    var event = new PacketReceiveEvent<>(
                            setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.ACCEPT_TELEPORTATION, packet);
                    new PacketServerTeleport().onAcceptTeleportation(event, setup.player, packet);
                    assertFalse(event.isCancelled(), "teleport " + id);
                }
                assertEquals(burst - id, teleports.pendingTeleports.size(), "teleport " + id);
                assertEquals(x, setup.player.x, 1.0E-12, "teleport " + id);
                assertFalse(teleports.hasWithheldJavaTeleport(), "teleport " + id);
            }
            assertTrue(teleports.pendingTeleports.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V26_2", "V26_3"})
    void aTeleportProcessedWithoutItsResponseIsWithheld(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            var teleports = setup.player.getSetbackTeleportUtil();
            teleports.addSentTeleport(new Vec3(2, 64, 2), 20, new RelativeFlag(0), true, 1);
            teleports.addSentTeleport(new Vec3(3, 64, 3), 22, new RelativeFlag(0), true, 2);
            // handleMovePlayer answers before the client handles the trailing ping, so until that pong it may be
            // in flight.
            setup.player.lastTransactionReceived.set(20);
            assertFalse(teleports.hasWithheldJavaTeleport());
            // From 26.3 a wrong position is still an answer; checkTeleportQueue judges it.
            var ack = new ServerboundAcceptTeleportation(
                    1, version == ProtocolVersion.V26_2 ? null : new Vec3d(9, 64, 9), 0, 0);
            var event = new PacketReceiveEvent<>(
                    setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.ACCEPT_TELEPORTATION, ack);
            new PacketServerTeleport().onAcceptTeleportation(event, setup.player, ack);
            if (version == ProtocolVersion.V26_2) setup.posRot(2);
            setup.player.lastTransactionReceived.set(22);
            assertFalse(teleports.hasWithheldJavaTeleport());

            setup.player.lastTransactionReceived.set(23);
            assertTrue(teleports.hasWithheldJavaTeleport());
        }
    }

    @Test
    void anIdBefore26_3IsNoAnswerWithoutThePosRotThatFollowsIt() throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_2)) {
            var teleports = setup.player.getSetbackTeleportUtil();
            int pending = 128;
            for (int id = 1; id <= pending; id++) {
                teleports.addSentTeleport(new Vec3(2, 64, 2), 20 + 2 * id, new RelativeFlag(0), true, id);
            }
            setup.player.lastTransactionReceived.set(22);
            var ack = new ServerboundAcceptTeleportation(1, null, 0, 0);
            var event = new PacketReceiveEvent<>(
                    setup.fixture.user, ConnectionPhase.PLAY, ServerboundPackets.ACCEPT_TELEPORTATION, ack);
            new PacketServerTeleport().onAcceptTeleportation(event, setup.player, ack);
            assertFalse(teleports.hasWithheldJavaTeleport());

            // handleMovePlayer sends the PosRot right after the ID, before the client handles the trailing ping.
            setup.player.lastTransactionReceived.set(23);
            assertTrue(teleports.hasWithheldJavaTeleport());
            assertEquals(pending, teleports.pendingTeleports.size());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V26_2", "V26_3"})
    void blockedReminderIsPredictedAndResetsTheCounter(ProtocolVersion version) throws Exception {
        try (var setup = new Setup(version)) {
            setup.pendingSetback();
            var reminder = setup.player.checkManager.getCheck(BadPacketsE.class);
            for (int i = 0; i < 19; i++) setup.rotationTick(i);
            assertEquals(0, reminder.violations);
            var runner = setup.player.checkManager.getSimulationProcessor();
            assertTrue(runner.getLastMovementTime().isEmpty());

            var position = setup.move(true, .5005, 20);
            assertTrue(position.isCancelled(), "The reminder must still be withheld from Paper");
            assertEquals(.5005, setup.player.x, 1.0E-12, "Prediction must observe the client position");
            assertEquals(1, runner.getLastMovementTime().size(), "Exactly one position prediction");
            assertEquals(0, intField(BadPacketsE.class, reminder, "noReminderTicks"));
            assertTrue(setup.player.getSetbackTeleportUtil().isPendingSetback());
            setup.endTick();
            assertEquals(1, runner.getLastMovementTime().size());

            for (int i = 0; i < 19; i++) setup.rotationTick(21 + i);
            assertEquals(0, reminder.violations, "Blocked reminders must prevent manufactured violations");
            setup.rotationTick(40);
            assertEquals(1, reminder.violations, "A genuinely missing reminder must still flag");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bed", "dead"})
    void invalidBedAndDeathMovementCannotChangeThePredictionAnchor(String reason) throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            if (reason.equals("bed")) {
                setup.player.isInBed = true;
                setup.player.bedPosition = new Vec3(5, 64, 5);
            } else {
                setup.player.compensatedEntities.getSelf().isDead = true;
            }
            var reminder = setup.player.checkManager.getCheck(BadPacketsE.class);
            setField(BadPacketsE.class, reminder, "noReminderTicks", 19);
            assertTrue(setup.move(true, .6, 0).isCancelled());
            assertEquals(.5, setup.player.x, 1.0E-12);
            assertEquals(19, intField(BadPacketsE.class, reminder, "noReminderTicks"));
            assertTrue(setup.player
                    .checkManager
                    .getSimulationProcessor()
                    .getLastMovementTime()
                    .isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Timer", "TickTimer", "DumbTimer"})
    void timerRejectedPositionNeverReachesPrediction(String timer) throws Exception {
        try (var setup = new Setup(ProtocolVersion.V26_3)) {
            if (timer.equals("DumbTimer")) {
                var dumb = setup.player.checkManager.getListener(DumbTimer.class);
                setField(DumbTimer.class, dumb, "balance", (double) System.nanoTime() + 1_000_000_000L);
            } else {
                Check ignored = timer.equals("Timer")
                        ? setup.player.checkManager.getCheck(TickTimer.class)
                        : setup.player.checkManager.getCheck(TimerCheck.class);
                setField(Check.class, ignored, "exemptPermission", true);
                assertFalse(setup.move(true, .5, 0).isCancelled());
                if (timer.equals("TickTimer")) {
                    // TickTimer requests a setback only above its configured VL threshold.
                    setup.player.checkManager.getCheck(TickTimer.class).violations = 1;
                }
            }
            var runner = setup.player.checkManager.getSimulationProcessor();
            int predictions = runner.getLastMovementTime().size();
            var reminder = setup.player.checkManager.getCheck(BadPacketsE.class);
            setField(BadPacketsE.class, reminder, "noReminderTicks", 19);

            assertTrue(setup.move(true, .6, 0).isCancelled());
            assertEquals(.5, setup.player.x, 1.0E-12);
            assertEquals(predictions, runner.getLastMovementTime().size());
            assertEquals(19, intField(BadPacketsE.class, reminder, "noReminderTicks"));
        }
    }

    private static int intField(Class<?> owner, Object value, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(value);
    }

    private static void setField(Class<?> owner, Object value, String name, Object setting) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(value, setting);
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services;
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final CheckManagerListener listener = new CheckManagerListener();

        Setup(ProtocolVersion version) throws Exception {
            services = new RecordConsumerServices(version);
            fixture = new RecordReceiveFixture(version);
            player = fixture.player;
            setField(CultPlayer.class, player, "resolvedClientVersion", ClientVersion.V_26_3);
            player.gamemode = GameMode.SURVIVAL;
            player.lastTransactionReceived.set(10);
            player.x = player.lastX = .5;
            player.y = player.lastY = 64;
            player.z = player.lastZ = .5;
            player.onGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, .5);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.STONE.defaultState());
                }
            }
            var teleports = player.getSetbackTeleportUtil();
            teleports.hasFullyLoaded = true;
            teleports.updateSafeVehiclePosition(new Vec3(.5, 64, .5));
            player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, -.0784000015258789, 0));
            // These synchronous fixtures exercise packet ordering, not wall-clock pacing.
            setField(
                    AbstractTimerCheck.class,
                    player.checkManager.getCheck(TimerCheck.class),
                    "timerBalanceRealTime",
                    System.nanoTime() - 10_000_000_000L);
            setField(
                    DumbTimer.class,
                    player.checkManager.getListener(DumbTimer.class),
                    "balance",
                    (double) System.nanoTime() - 10_000_000_000L);
        }

        void pendingSetback() throws Exception {
            var data = new SetBackData(
                    new TeleportData(new Vec3(2, 64, 2), new RelativeFlag(0), 20, 1), 0, 0, Vec3.ZERO, false, false);
            setField(SetbackTeleportUtil.class, player.getSetbackTeleportUtil(), "requiredSetBack", data);
            assertTrue(player.getSetbackTeleportUtil().shouldBlockMovement());
        }

        PacketReceiveEvent<ServerboundMovePlayer> move(boolean position, double x, float yaw) {
            var packet = new ServerboundMovePlayer(x, 64, .5, yaw, 0, true, false, position, true);
            var event = new PacketReceiveEvent<>(
                    fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet);
            listener.onMovePlayer(event, player, packet);
            return event;
        }

        void posRot(double xz) {
            var packet = new ServerboundMovePlayer(xz, 64, xz, 0, 0, false, false, true, true);
            listener.onMovePlayer(
                    new PacketReceiveEvent<>(fixture.user, ConnectionPhase.PLAY, ServerboundPackets.MOVE_PLAYER, packet),
                    player,
                    packet);
        }

        void rotationTick(float yaw) {
            move(false, .5, yaw);
            endTick();
        }

        void endTick() {
            listener.processClientTickEndReceive(
                    new PacketReceiveEvent<>(
                            fixture.user,
                            ConnectionPhase.PLAY,
                            ServerboundPackets.CLIENT_TICK_END,
                            ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                    player);
        }

        @Override
        public void close() throws Exception {
            fixture.close();
            services.close();
        }
    }
}
