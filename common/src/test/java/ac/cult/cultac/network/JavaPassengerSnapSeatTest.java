package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketServerTeleport;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundTeleportEntity;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A passenger seated in cobwebs on a block display that is teleported away. ClientPacketListener#handleTeleportEntity
 * interpolates a root the client does not move (setValuesFromPositionPacket), but a jump farther than 64 blocks is
 * set at once and Entity#positionRider seats the player there: the next Entity#rideTick travels from the new seat, out
 * of the webs, and its move spends the old cobweb multiplier. A nearer teleport leaves the passenger at the old seat
 * until that tick's positionRider, so its travel meets the webs again and the first move after the dismount is slowed.
 */
class JavaPassengerSnapSeatTest {
    private static final int VEHICLE = 100;
    private static final Vec3 DISPLAY = new Vec3(6.5, FLOOR_Y + 1.6, 0.5);
    private static final Vec3 COBWEB = new Vec3(0.25, 0.05F, 0.25);
    private static final double FALL = -0.08 * 0.98F;
    private static final double PUSH = 0.5;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @CsvSource({"V_1_21_11, 70, false", "V_1_21_11, 30, true", "V_26_2, 70, false", "V_26_2, 30, true",
        "V_26_3, 70, false", "V_26_3, 30, true"})
    void theRideTickAfterAFarTeleportTravelsFromTheNewSeat(ClientVersion version, int distance, boolean slowed)
            throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = 0; x <= 6; x++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, 0);
            f.input(NONE);
            f.player.compensatedWorld.updateBlock(new BlockPos(6, FLOOR_Y + 1, 0), BlockIds.COBWEB.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(6, FLOOR_Y + 2, 0), BlockIds.COBWEB.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 standing = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(standing, new Vec3(0, FALL, 0));
            PredictionResult stand = f.tick(standing, true);
            assertTrue(exact(stand), describe(stand));

            f.player.compensatedEntities.addEntity(VEHICLE, EntityTypeIds.BLOCK_DISPLAY, DISPLAY, 0, 0, 0);
            Vec3 seat = DISPLAY.add(0, -0.6, 0);
            f.place(seat, new Vec3(0, FALL, 0));
            f.setPassengers(VEHICLE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            for (int tick = 0; tick < 3; tick++) f.rideTick();

            Vec3 target = DISPLAY.add(distance, 0, 0);
            teleportVehicle(f, target);
            f.rideTick();
            dismount(f);
            // ServerPlayer#dismountTo's teleport onto the new seat keeps Entity#stuckSpeedMultiplier.
            Vec3 newSeat = target.add(0, -0.6, 0);
            f.teleport(newSeat, new ac.cult.cultac.network.protocol.teleport.RelativeFlag(0));

            Vec3 from = new Vec3(f.player.x, f.player.y, f.player.z);
            f.pongThrough(f.velocity(new Vec3(PUSH, 0, 0)));
            PredictionResult move = f.tick(from.add(slowed ? PUSH * COBWEB.x : PUSH, 0, 0), false);
            assertEquals(newSeat, from);
            assertTrue(exact(move), version + " teleported " + distance + ": " + describe(move));
        }
    }

    private static void teleportVehicle(JavaTickFixture f, Vec3 to) {
        var packet =
                new ClientboundTeleportEntity(VEHICLE, new Vec3d(to.x, to.y, to.z), Vec3d.ZERO, 0, 0, Set.of(), false);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.TELEPORT_ENTITY, packet, false);
        f.player.packetEntityReplication.onTeleportEntity(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }

    /** The dismount, and the resync teleport the passenger packet sends to the last ride tick's seat. */
    private static void dismount(JavaTickFixture f) {
        var before = new ArrayList<Integer>();
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports)
            before.add(teleport.getTeleportId());
        f.setPassengers(VEHICLE, List.of());
        f.pongThrough(f.player.lastTransactionSent.get());
        for (TeleportData teleport : f.player.getSetbackTeleportUtil().pendingTeleports) {
            if (before.contains(teleport.getTeleportId())) continue;
            Vec3 location = teleport.getLocation();
            var packet = new ServerboundAcceptTeleportation(
                    teleport.getTeleportId(),
                    new Vec3d(location.x, location.y, location.z),
                    teleport.getFinalYaw(),
                    teleport.getFinalPitch());
            var event = f.event(ServerboundPackets.ACCEPT_TELEPORTATION, packet);
            new PacketServerTeleport().onAcceptTeleportation(event, f.player, packet);
            assertFalse(event.isCancelled(), "the dismount teleport acceptance was rejected");
        }
        assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
    }
}
