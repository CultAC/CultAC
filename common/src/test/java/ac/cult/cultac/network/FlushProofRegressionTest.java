package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsVehicle;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.*;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.protocol.wire.Wire;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Receipt batching must preserve packet identity, wire order and immediate vanilla responses. */
class FlushProofRegressionTest {
    @Test
    void takingOnlyTheKnockbackCannotSatisfyAnExplosionSharingItsPing() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            f.place(new Vec3(.5, 64, .5), Vec3.ZERO);
            open(f.fixture);
            send(f.fixture, new ClientboundEntityMotion(f.player.entityID, new Vec3d(.5, 0, 0)));
            send(f.fixture, new ClientboundExplode(Vec3d.ZERO, new Vec3d(.2, 0, 0)));
            var kb = f.player.checkManager.getKnockbackHandler().getLastSent();
            var explosion = f.player.checkManager.getExplosionHandler().getLastSent();
            assertEquals(kb.getTransaction(), explosion.getTransaction());
            assertNotEquals(kb, explosion);
            var onlyKb = new PredVector(kb.getVel());
            onlyKb.addPacketModifier(kb);
            assertFalse(onlyKb.hasPacketModifier(explosion));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.tick(new Vec3(1, 64, .5), true);
            assertTrue(f.player.checkManager.getExplosionHandler().violations > 0);
        }
    }

    @Test
    void explosionVelocityExplosionRetainsOnlyTheLastAddend() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            f.place(new Vec3(.5, 64, .5), Vec3.ZERO);
            open(f.fixture);
            send(f.fixture, new ClientboundExplode(Vec3d.ZERO, new Vec3d(.3, 0, 0)));
            send(f.fixture, new ClientboundEntityMotion(f.player.entityID, new Vec3d(.5, 0, 0)));
            send(f.fixture, new ClientboundExplode(Vec3d.ZERO, new Vec3d(.2, 0, 0)));
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(
                    .2, f.player.checkManager.getExplosionHandler().secondBread.getVel().x, 1e-12);
            var result = f.tick(new Vec3(1.2, 64, .5), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
            assertEquals(0, f.player.checkManager.getKnockbackHandler().violations);
        }
    }

    @Test
    void explosionBeforeVelocityIsAppliedInWireOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            f.place(new Vec3(.5, 64, .5), Vec3.ZERO);
            open(f.fixture);
            send(f.fixture, new ClientboundExplode(Vec3d.ZERO, new Vec3d(.3, 0, 0)));
            send(f.fixture, new ClientboundEntityMotion(f.player.entityID, new Vec3d(.5, 0, 0)));
            f.pongThrough(f.player.lastTransactionSent.get());
            var result = f.tick(new Vec3(1, 64, .5), true);
            assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            assertEquals(0, f.player.checkManager.getExplosionHandler().violations);
        }
    }

    @Test
    void aSetbackCarriesAnExplosionSentAfterAMotionInTheSameFlush() throws Exception {
        // ClientPacketListener#handleExplosion pushes onto the motion before it, though one ping proves both.
        assertEquals(
                setbackVelocityAfter(new Vec3d(.7, 0, 0), null),
                setbackVelocityAfter(new Vec3d(.5, 0, 0), new Vec3d(.2, 0, 0)));
    }

    private static Vec3 setbackVelocityAfter(Vec3d motion, Vec3d explosion) throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            Vec3 start = new Vec3(.5, JavaTickFixture.FLOOR_Y + 6, .5);
            f.place(start, Vec3.ZERO);
            f.tick(start.add(0, -.0784, 0), false);
            open(f.fixture);
            send(f.fixture, new ClientboundEntityMotion(f.player.entityID, motion));
            if (explosion != null) {
                send(f.fixture, new ClientboundExplode(Vec3d.ZERO, explosion));
                assertEquals(
                        f.player.checkManager.getKnockbackHandler().getLastSent().getTransaction(),
                        f.player.checkManager.getExplosionHandler().getLastSent().getTransaction());
            }
            // The client has the chunk it stands in once it answers a ping sent after it.
            f.pongThrough(f.player.sendTrailingTransaction());
            var teleports = f.player.getSetbackTeleportUtil();
            assertTrue(teleports.executeViolationSetback());
            return teleports.getRequiredSetBack().getVelocity();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_3", "V1_21_11", "V26_2", "V26_3"})
    void projectileMotionWaitsForReceiptAndSharesTheFlushPing(ProtocolVersion version) {
        try (var f = RecordReceiveFixture.olderHost(version)) {
            prepare(f);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.WIND_CHARGE, new Vec3(5, 64, 5), 0, 0, 0);
            var projectile = f.player.compensatedEntities.getEntity(100);
            open(f);
            acknowledge(f, f.player.lastTransactionSent.get());
            f.channel.flush();
            f.channel.runPendingTasks();
            int previousProof = f.player.lastTransactionSent.get();
            send(f, new ClientboundEntityMotion(100, new Vec3d(0, 0, 2)));
            int proof = f.player.lastTransactionSent.get();
            assertTrue(proof > previousProof, "an already acknowledged ping cannot prove newly sent projectile motion");
            send(f, new ClientboundEntityMotion(100, new Vec3d(0, 0, 1)));
            assertEquals(proof, f.player.lastTransactionSent.get(), "projectiles share one flush ping");
            assertEquals(Vec3.ZERO, projectile.deltaMovement, "old client ticks cannot see unreceived motion");
            acknowledge(f, proof);
            assertEquals(1, projectile.deltaMovement.z, 1e-12);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProtocolVersion.class,
            names = {"V1_21_3", "V1_21_11", "V26_2", "V26_3"})
    void windowProofsAreAcknowledgedInMonotonicWireOrder(ProtocolVersion version) {
        try (var f = RecordReceiveFixture.olderHost(version)) {
            prepare(f);
            var screen = f.id(ClientboundPackets.OPEN_SCREEN, "open_screen");
            Wire.writeVarInt(screen, 7);
            Wire.writeVarInt(
                    screen,
                    ac.cult.cultac.network.codec.ModelRegistryNamesState.defaults()
                            .id("minecraft:menu", "minecraft:generic_9x3"));
            // Anonymous NBT compound containing the screen title.
            screen.writeByte(10).writeByte(8).writeShort(4).writeBytes(new byte[] {'t', 'e', 'x', 't'});
            screen.writeShort(5)
                    .writeBytes(new byte[] {'C', 'h', 'e', 's', 't'})
                    .writeByte(0);
            f.channel.write(screen);
            f.channel.flush();
            f.channel.runPendingTasks();
            var transactions = new ArrayList<Integer>();
            ByteBuf frame;
            while ((frame = f.channel.readOutbound()) != null) {
                try {
                    int id = Wire.readVarInt(frame);
                    if ("minecraft:ping"
                            .equals(f.data.packets(
                                            ac.cult.cultac.protocol.ConnectionPhase.PLAY,
                                            ac.cult.cultac.protocol.PacketDirection.CLIENTBOUND)
                                    .name(id))) {
                        assertTrue(f.player.addTransactionResponse(frame.readInt()));
                        transactions.add(f.player.lastTransactionReceived.get());
                    }
                } finally {
                    frame.release();
                }
            }
            assertEquals(java.util.List.of(1, 2), transactions);
            assertEquals(f.player.lastTransactionSent.get(), f.player.lastTransactionReceived.get());
            assertEquals(7, f.player.getInventory().openWindowID);
            var ran = new java.util.concurrent.atomic.AtomicBoolean();
            f.player.latencyUtils.addRealTimeTask(f.player.lastTransactionSent.get(), () -> ran.set(true));
            assertTrue(ran.get(), "an already answered proof releases newly scheduled work immediately");
        }
    }

    @Test
    void aVehicleTeleportEchoPrecedesTheProofForLaterMotion() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, .5), 0, 0, 0);
            var boat = f.player.compensatedEntities.getEntity(100);
            f.setPassengers(100, java.util.List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.fixture.channel.releaseOutbound();
            open(f.fixture);
            int earlierProof = f.player.lastTransactionSent.get();
            send(
                    f.fixture,
                    new ClientboundTeleportEntity(100, new Vec3d(128.5, 64, .5), Vec3d.ZERO, 0, 0, Set.of(), false));
            int responseProof = f.player.lastTransactionSent.get();
            assertTrue(responseProof > earlierProof);
            send(f.fixture, new ClientboundEntityMotion(100, new Vec3d(1, 0, 0)));
            f.pongThrough(earlierProof);
            var echo = new ServerboundMoveVehicle(new Vec3d(128.5, 64, .5), 0, 0, false, false);
            var event = f.event(ServerboundPackets.MOVE_VEHICLE, echo);
            f.listener.onMoveVehicle(event, f.player, echo);
            assertFalse(event.isCancelled());
            f.pongThrough(f.player.lastTransactionSent.get());
            assertEquals(1, boat.deltaMovement.x, 1e-12);
        }
    }

    @Test
    void aMoveVehicleEchoPrecedesTheProofForALaterDismount() throws Exception {
        try (var f = new JavaTickFixture()) {
            prepare(f.fixture);
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.OAK_BOAT, new Vec3(.5, 64, .5), 0, 0, 0);
            f.setPassengers(100, java.util.List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.fixture.channel.releaseOutbound();
            open(f.fixture);
            send(f.fixture, new ClientboundMoveVehicle(new Vec3d(8.5, 64, .5), 0, 0));
            send(f.fixture, new ClientboundSetPassengers(100, java.util.List.of()));
            // ClientPacketListener#handleMoveVehicle sends the echo before the client handles any later packet.
            answerPingsBefore(f.fixture, "minecraft:move_vehicle");
            var echo = new ServerboundMoveVehicle(new Vec3d(8.5, 64, .5), 0, 0, false, false);
            var event = f.event(ServerboundPackets.MOVE_VEHICLE, echo);
            f.listener.onMoveVehicle(event, f.player, echo);
            assertFalse(event.isCancelled());
            assertEquals(0, f.player.checkManager.getCheck(BadPacketsVehicle.class).violations);
            f.pongThrough(f.player.lastTransactionSent.get());
            assertNull(f.player.compensatedEntities.getSelf().getRiding());
        }
    }

    /** Answers, in wire order, each ping written before the first {@code name} packet. */
    private static void answerPingsBefore(RecordReceiveFixture f, String name) {
        f.channel.flush();
        f.channel.runPendingTasks();
        var packets = f.data.packets(
                ac.cult.cultac.protocol.ConnectionPhase.PLAY, ac.cult.cultac.protocol.PacketDirection.CLIENTBOUND);
        ByteBuf frame;
        while ((frame = f.channel.readOutbound()) != null) {
            try {
                String packet = packets.name(Wire.readVarInt(frame));
                if (name.equals(packet)) return;
                if ("minecraft:ping".equals(packet)) assertTrue(f.player.addTransactionResponse(frame.readInt()));
            } finally {
                frame.release();
            }
        }
        fail(name + " was not written");
    }

    private static void prepare(RecordReceiveFixture f) {
        f.user.bind(mock(PlatformPlayer.class));
        f.player.nettyScheduler.removeScheduler();
        f.channel.runPendingTasks();
        f.registrar.register(routes -> {
            routes.send(
                    ClientboundPackets.PING,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> new ac.cult.cultac.events.packets.listeners.PacketPingListener()
                            .onPing(event, player, packet));
            routes.send(
                    ClientboundPackets.SET_CAMERA,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.appendFlushProofTransaction(event));
            routes.send(ClientboundPackets.ENTITY_MOTION, PacketListenerPriority.LOW, (event, player, packet) -> {
                if (packet.entityId() == -100) player.appendFlushProofTransaction(event);
                else player.packetEntityReplication.onSetEntityMotion(event, player, packet);
            });
            routes.send(
                    ClientboundPackets.EXPLODE,
                    PacketListenerPriority.LOW,
                    (event, player, packet) ->
                            player.checkManager.getExplosionHandler().onExplode(event, player, packet));
            routes.send(
                    ClientboundPackets.MOVE_VEHICLE,
                    PacketListenerPriority.LOW,
                    (event, player, packet) ->
                            new ac.cult.cultac.events.packets.listeners.PacketServerTeleport()
                                    .onMoveVehicle(event, player, packet));
            routes.send(
                    ClientboundPackets.SET_PASSENGERS,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onSetPassengers(event, player, packet));
            routes.send(
                    ClientboundPackets.TELEPORT_ENTITY,
                    PacketListenerPriority.LOW,
                    (event, player, packet) -> player.packetEntityReplication.onTeleportEntity(event, player, packet));
            routes.send(ClientboundPackets.OPEN_SCREEN, PacketListenerPriority.LOW, (event, player, packet) -> {
                new PacketPlayerWindow().onOpenScreen(event, player, packet);
                player.getInventory().onOpenScreen(event, player, packet);
            });
        });
    }

    private static void open(RecordReceiveFixture f) {
        send(f, new ClientboundEntityMotion(-100, Vec3d.ZERO));
        assertTrue(f.connection.flushBundleOpen());
    }

    private static void send(RecordReceiveFixture f, ClientboundPacket packet) {
        var result = f.channel.write(new CultWrite(packet, false));
        assertFalse(result.isDone() && !result.isSuccess(), () -> String.valueOf(result.cause()));
    }

    private static void acknowledge(RecordReceiveFixture f, int transaction) {
        f.player.lastTransactionReceived.set(transaction);
        f.player.latencyUtils.handleNettySyncTransaction(transaction);
    }
}
