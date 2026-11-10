package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.observeOnly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRemoveEntities;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The local player drives a boat resting on the edge of a pillar, its seat over the floor, and the server removes the
 * boat. ClientPacketListener#handleRemoveEntities -> Entity#setRemoved stops the passenger riding, and client-side
 * LivingEntity#stopRiding moves nothing, so the player stays where the last Entity#positionRider seated it: 0.1875 up
 * the boat (AbstractBoat#getPassengerAttachmentPoint) less the player's 0.6 vehicle attachment. The MoveVehicle
 * packets carry the boat's position, not the rider's.
 */
class DismountRemovedControlledVehicleSeatTest {
    private static final int BOAT = 100;
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** The boat's box overhangs the pillar by 0.0875; the rider's box is clear of it. */
    private static final Vec3 BOAT_AT = new Vec3(0.6, FLOOR_Y + 1, 0.5);
    /** LivingEntity#travelInAir: one tick of gravity from the zero delta Entity#rideTick left, then 0.98 drag. */
    private static final double FALL = -0.08 * 0.98F;

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (ClientVersion version :
                List.of(ClientVersion.V_1_21_2, ClientVersion.V_1_21_9, ClientVersion.V_26_2, ClientVersion.V_26_3)) {
            cases.add(Arguments.of(version, false));
            cases.add(Arguments.of(version, true));
        }
        return cases.stream();
    }

    /**
     * Enforcing, the removal's zero entity motion leaves the first free move at the seat; observing only, that move
     * falls by the delta the last ride tick's own travel left.
     */
    @ParameterizedTest(name = "{0}, observe-only {1}")
    @MethodSource("cases")
    void theRiderOfARemovedBoatStartsFromItsSeat(ClientVersion version, boolean observing) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = observing ? observeOnly() : (AutoCloseable) () -> {}) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int z = -1; z <= 1; z++)
                f.player.compensatedWorld.updateBlock(new BlockPos(-1, FLOOR_Y, z), BlockIds.STONE.defaultState());
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(BOAT_AT, Vec3.ZERO);
            f.player.compensatedEntities.addEntity(BOAT, EntityTypeIds.OAK_BOAT, BOAT_AT, 0, 0, 0);
            f.setPassengers(BOAT, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            f.input(NONE);
            for (int tick = 0; tick < 3; tick++) assertVehicleExact(rideTick(f), tick);

            var packet = new ClientboundRemoveEntities(List.of(BOAT));
            var event = new PacketSendEvent<>(
                    f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.REMOVE_ENTITIES, packet, false);
            f.player.packetEntityReplication.onRemoveEntities(event, f.player, packet);
            event.tasksAfter().forEach(Runnable::run);
            f.pongThrough(f.player.lastTransactionSent.get());

            Vec3 seat = new Vec3(BOAT_AT.x, BOAT_AT.y + 0.1875 - 0.6, BOAT_AT.z);
            PredictionResult free = f.tick(observing ? seat.add(0, FALL, 0) : seat, false);
            assertTrue(exact(free), describe(free));
        }
    }

    /** Player#tick clears the passenger's ground bit while the boat remains grounded. */
    private static PredictionResult rideTick(JavaTickFixture f) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var rot = new ServerboundMovePlayer(0, 0, 0, 0, 0, false, false, false, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, rot), f.player, rot);
        f.moveVehicle(BOAT_AT, 0, 0, true);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertTrue(result != null && result != before, "the vehicle move was not predicted");
        return result;
    }
}
