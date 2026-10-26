package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 server announces the end of a boat's bubble column countdown with entity event 71 or 72.
 * AbstractBoat#handleEntityEvent applies it from 26.3; a 26.1 AbstractBoat has no handler, and its
 * AbstractBoat#tickBubbleColumn changes the velocity on the server only.
 */
class BoatBubbleColumnEventTest {
    private static final int BOAT = 100;
    private static final byte BUBBLE_COLUMN_UP = 72;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void a26_1BoatKeepsItsVelocityThroughABubbleColumnEvent() throws Exception {
        try (var f = mount(ClientVersion.V_26_1)) {
            bubbleColumnEvent(f);
            for (int tick = 0; tick < 3; tick++) assertVehicleExact(f.rideTick(SEAT, true), tick);
        }
    }

    /**
     * AbstractBoat#handleBubbleColumnEffect sets the controlled boat's Y velocity to 0.6, and
     * AbstractBoat#floatBoat on land adds the boat's -0.04 gravity before the move.
     */
    @Test
    void a26_3BoatRisesOnABubbleColumnEvent() throws Exception {
        try (var f = mount(ClientVersion.V_26_3)) {
            bubbleColumnEvent(f);
            assertVehicleExact(f.rideTick(SEAT.add(0, 0.6 - 0.04, 0), false), 0);
        }
    }

    private static JavaTickFixture mount(ClientVersion client) throws Exception {
        var f = new JavaTickFixture(ProtocolVersion.V26_3, client);
        f.place(SEAT, Vec3.ZERO);
        f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, SEAT, 0, 0, 0);
        f.setPassengers(BOAT, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.player.xRot = 0;
        f.player.yRot = 0;
        f.input(NONE);
        f.tickEnd();
        assertVehicleExact(f.rideTick(SEAT, true), -1);
        return f;
    }

    private static void bubbleColumnEvent(JavaTickFixture f) {
        var packet = new ClientboundEntityEvent(BOAT, BUBBLE_COLUMN_UP);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ENTITY_EVENT, packet, false);
        f.player.packetEntityReplication.onEntityEvent(event, f.player, packet);
        event.getTasksAfterSend().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
    }
}
