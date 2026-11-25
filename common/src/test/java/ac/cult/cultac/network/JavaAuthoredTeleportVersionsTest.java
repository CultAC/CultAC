package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.movement.timer.AbstractTimerCheck;
import ac.cult.cultac.checks.impl.movement.timer.DumbTimer;
import ac.cult.cultac.checks.impl.movement.timer.TimerCheck;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/** A proxy's own teleport is real client state, but neither part of its reply belongs to the backend. */
class JavaAuthoredTeleportVersionsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void authoredRepliesAreConsumedAndTheFollowingOrdinaryPositionIsPreserved(String release, ClientVersion version)
            throws Exception {
        try (var f = new Setup(version)) {
            int proof = f.send(true, 77, 2.5);
            f.reply(proof, true, 77, 2.5);
            assertEquals(2.5, f.player.x, 0.0, "the local teleport must still update compensation");
            assertTrue(f.player.getSetbackTeleportUtil().pendingTeleports.isEmpty());
            f.frame(f.position(2.5), false);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void negativeBackendIdsRetainTheirWholeReply(String release, ClientVersion version) throws Exception {
        try (var f = new Setup(version)) {
            int proof = f.send(false, -71, 2.5);
            f.reply(proof, false, -71, 2.5);
            assertEquals(2.5, f.player.x, 0.0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void ownershipFollowsTheSentPacketsWhenABackendAndProxyReuseTheSameId(String release, ClientVersion version)
            throws Exception {
        for (boolean authoredFirst : new boolean[] {false, true}) {
            try (var f = new Setup(version)) {
                int first = f.send(authoredFirst, -1485371877, 2.5);
                int second = f.send(!authoredFirst, -1485371877, 3.5);
                f.reply(first, authoredFirst, -1485371877, 2.5);
                f.reply(second, !authoredFirst, -1485371877, 3.5);
                assertEquals(3.5, f.player.x, 0.0);
                assertTrue(f.player.getSetbackTeleportUtil().pendingTeleports.isEmpty());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void observeOnlyStartingInFlightStillConsumesTheProxyHousekeepingReply(String release, ClientVersion version)
            throws Exception {
        try (var f = new Setup(version)) {
            int proof = f.send(true, 79, 2.5);
            try (var ignored = RecordReceiveFixture.observeOnly(true)) {
                f.reply(proof, true, 79, 2.5);
                assertEquals(2.5, f.player.x, 0.0);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void loginDiscardsThePreviousBackendsReceiptOwnership(String release, ClientVersion version) throws Exception {
        try (var f = new Setup(version)) {
            f.send(true, 81, 2.5);
            f.player.getSetbackTeleportUtil().loginSent();
            int proof = f.send(false, 81, 3.5);
            f.reply(proof, false, 81, 3.5);
            assertEquals(3.5, f.player.x, 0.0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void repeatedAuthoredIdsKeepBothReceiptsPrivate(String release, ClientVersion version) throws Exception {
        try (var f = new Setup(version)) {
            int first = f.send(true, 83, 2.5);
            int second = f.send(true, 83, 3.5);
            f.reply(first, true, 83, 2.5);
            f.reply(second, true, 83, 3.5);
            assertEquals(3.5, f.player.x, 0.0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void theActualSilentSetbackEmitterAlsoKeepsItsReceiptPrivate(String release, ClientVersion version)
            throws Exception {
        try (var f = new Setup(version)) {
            int initial = f.send(false, 89, 2.5);
            f.reply(initial, false, 89, 2.5);
            f.frame(f.position(2.5), false);
            var teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());
            var sent = teleports.pendingTeleports.getLast();
            assertEquals(64.0, sent.getLocation().y, 0.0);
            assertEquals(.5, sent.getLocation().z, 0.0);
            f.reply(sent.getTransaction(), true, sent.getTeleportId(), sent.getLocation().x);
            assertFalse(teleports.isPendingSetback(), "the consumed receipt must still complete the setback");
        }
    }

    private static final class Setup implements AutoCloseable {
        final RecordConsumerServices services = new RecordConsumerServices(ProtocolVersion.V26_3);
        final RecordReceiveFixture fixture;
        final CultPlayer player;
        final ClientVersion version;

        Setup(ClientVersion version) throws Exception {
            this.version = version;
            fixture = RecordReceiveFixture.olderHost(ProtocolVersion.of(version.getProtocolVersion()));
            services.useNetwork(fixture.manager);
            player = fixture.player;
            fixture.user.bind(Mockito.mock(PlatformPlayer.class));
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", version);
            player.gamemode = GameMode.SURVIVAL;
            player.x = player.lastX = .5;
            player.y = player.lastY = 64;
            player.z = player.lastZ = .5;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, .5, 64, .5);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++)
                    player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.STONE.defaultState());
            }
            JavaTickFixture.setField(
                    AbstractTimerCheck.class,
                    player.checkManager.getCheck(TimerCheck.class),
                    "timerBalanceRealTime",
                    System.nanoTime() - 10_000_000_000L);
            JavaTickFixture.setField(
                    DumbTimer.class,
                    player.checkManager.getListener(DumbTimer.class),
                    "balance",
                    (double) System.nanoTime() - 10_000_000_000L);
            var teleports = new PacketServerTeleport();
            var listener = new CheckManagerListener();
            fixture.registrar.register(routes -> {
                routes.send(
                        ClientboundPackets.PLAYER_POSITION, PacketListenerPriority.NORMAL, teleports::onPlayerPosition);
                routes.receive(
                        ServerboundPackets.ACCEPT_TELEPORTATION,
                        PacketListenerPriority.NORMAL,
                        teleports::onAcceptTeleportation);
                routes.receive(ServerboundPackets.MOVE_PLAYER, PacketListenerPriority.NORMAL, listener::onMovePlayer);
            });
        }

        int send(boolean authored, int id, double x) {
            var packet = new ClientboundPlayerPosition(id, new Vec3d(x, 64, .5), Vec3d.ZERO, 0, 0, Set.of());
            if (authored) fixture.channel.writeOutbound(new CultWrite(packet, false));
            else {
                var encoded = fixture.connection
                        .packets()
                        .encode(
                                ConnectionPhase.PLAY,
                                ClientboundPackets.PLAYER_POSITION,
                                packet,
                                fixture.channel.alloc(),
                                fixture.connection);
                assertEquals(1, encoded.frames().size());
                fixture.channel.writeOutbound(encoded.frames().getFirst());
            }
            ByteBuf frame;
            while ((frame = fixture.channel.readOutbound()) != null) frame.release();
            var sent = player.getSetbackTeleportUtil().pendingTeleports.getLast();
            assertEquals(id, sent.getTeleportId());
            return sent.getTransaction();
        }

        void reply(int proof, boolean authored, int id, double x) {
            player.lastTransactionReceived.set(proof);
            if (version.isOlderThan(ClientVersion.V_1_21_4)) {
                frame(position(x), authored);
                frame(ack(id, x), authored);
            } else {
                frame(ack(id, x), authored);
                if (version.isOlderThan(ClientVersion.V_26_3)) frame(position(x), authored);
            }
        }

        ByteBuf position(double x) {
            return fixture.id(ServerboundPackets.MOVE_PLAYER, "move_player_pos_rot")
                    .writeDouble(x)
                    .writeDouble(64)
                    .writeDouble(.5)
                    .writeFloat(0)
                    .writeFloat(0)
                    .writeByte(0);
        }

        ByteBuf ack(int id, double x) {
            var bytes = fixture.id(ServerboundPackets.ACCEPT_TELEPORTATION, "accept_teleportation");
            Wire.writeVarInt(bytes, id);
            if (version.isNewerThanOrEquals(ClientVersion.V_26_3))
                bytes.writeDouble(x)
                        .writeDouble(64)
                        .writeDouble(.5)
                        .writeFloat(0)
                        .writeFloat(0);
            return bytes;
        }

        void frame(ByteBuf bytes, boolean privateReceipt) {
            String expected = ByteBufUtil.hexDump(bytes);
            fixture.channel.writeInbound(bytes);
            ByteBuf forwarded = fixture.channel.readInbound();
            if (privateReceipt) {
                if (forwarded != null) {
                    forwarded.release();
                    fail("A proxy-owned teleport receipt reached the backend");
                }
            } else {
                assertNotNull(forwarded, "A backend receipt or ordinary movement was consumed");
                try {
                    assertEquals(expected, ByteBufUtil.hexDump(forwarded));
                } finally {
                    forwarded.release();
                }
            }
        }

        @Override
        public void close() throws Exception {
            fixture.close();
            services.close();
        }
    }
}
