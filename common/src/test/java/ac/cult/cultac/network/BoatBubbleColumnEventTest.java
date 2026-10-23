package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 26.3 AbstractBoat#handleEntityEvent applies BOAT_SINK (71) and BOAT_LAUNCH (72) to a boat the local
 * player controls. 26.2's EntityEvent ends at 70 and its AbstractBoat has no handleEntityEvent override.
 */
class BoatBubbleColumnEventTest {
    private static final int BOAT = 100;
    private static final byte BOAT_SINK = 71;
    private static final byte BOAT_LAUNCH = 72;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 DRIFT = new Vec3(0.1, -0.04, 0.2);

    @Test
    void a263ClientLaunchesAndSinksItsBoat() throws Exception {
        try (var f = new JavaTickFixture()) {
            PacketEntity boat = ridden(f);
            event(f, BOAT_LAUNCH);
            assertEquals(new Vec3(DRIFT.x, 0.6, DRIFT.z), boat.deltaMovement);
            event(f, BOAT_SINK);
            assertEquals(new Vec3(DRIFT.x, 0.6 - 0.7, DRIFT.z), boat.deltaMovement);
        }
    }

    @Test
    void a262ClientIgnoresTheBoatEvents() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_2);
            PacketEntity boat = ridden(f);
            event(f, BOAT_LAUNCH);
            event(f, BOAT_SINK);
            assertEquals(DRIFT, boat.deltaMovement);
        }
    }

    /** An oak boat the player drives, mounted through set_passengers, drifting by {@link #DRIFT}. */
    private static PacketEntity ridden(JavaTickFixture f) {
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        var packet = new ClientboundSetPassengers(BOAT, List.of(f.player.entityID));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        PacketEntity boat = f.player.compensatedEntities.getEntity(BOAT);
        assertTrue(f.player.compensatedEntities.getSelf().getRiding() == boat);
        boat.deltaMovement = DRIFT;
        return boat;
    }

    private static void event(JavaTickFixture f, byte status) {
        var packet = new ClientboundEntityEvent(BOAT, status);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
