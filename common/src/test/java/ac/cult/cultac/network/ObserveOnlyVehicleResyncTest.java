package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveVehicle;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerPosition;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetPassengers;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObserveOnlyVehicleResyncTest {
    @Test
    void mountingAndDismountingWriteOnlyTheProofInObserveOnly() throws Exception {
        for (boolean observeOnly : new boolean[] {false, true}) {
            try (var f = new JavaTickFixture();
                    var ignored = observeOnly(observeOnly)) {
                f.player.compensatedEntities.addEntity(
                        100, EntityTypeIds.OAK_BOAT, new Vec3(0.5, FLOOR_Y, 0.5), 0, 0, 0);
                f.player.compensatedEntities.serverPositionsMap.put(
                        100, new TrackerData(0.5, FLOOR_Y, 0.5, 0, 0, EntityTypeIds.OAK_BOAT, 0));

                List<Object> mount = passengers(f, List.of(f.player.entityID));
                assertEquals(!observeOnly, has(mount, ClientboundMoveVehicle.class), "observeOnly=" + observeOnly);
                assertEquals(!observeOnly, has(mount, ClientboundEntityMotion.class), "observeOnly=" + observeOnly);
                assertTrue(has(mount, ClientboundPing.class));
                assertTrue(f.player.compensatedEntities.getSelf().inVehicle());
                // A handler echo precedes the trailing pong. Once the proof is answered, neither the snap nudge
                // nor its return target may remain available to consume a later client tick.
                assertEquals(
                        0,
                        f.player.getSetbackTeleportUtil().queuedVehicleTeleportCount(),
                        "observeOnly=" + observeOnly);

                List<Object> dismount = passengers(f, List.of());
                assertEquals(
                        !observeOnly, has(dismount, ClientboundPlayerPosition.class), "observeOnly=" + observeOnly);
                assertEquals(!observeOnly, has(dismount, ClientboundEntityMotion.class), "observeOnly=" + observeOnly);
                assertTrue(has(dismount, ClientboundPing.class));
                assertFalse(f.player.compensatedEntities.getSelf().inVehicle());
                assertEquals(Vec3.ZERO, f.player.compensatedEntities.getSelf().deltaMovement);
            }
        }
    }

    /** The writes a set_passengers for vehicle 100 adds, after the client answers its proof. */
    private static List<Object> passengers(JavaTickFixture f, List<Integer> passengers) {
        var packet = new ClientboundSetPassengers(100, passengers);
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.SET_PASSENGERS, packet, false);
        f.player.packetEntityReplication.onSetPassengers(event, f.player, packet);
        event.tasksAfter().forEach(Runnable::run);
        f.pongThrough(f.player.lastTransactionSent.get());
        return event.writesAfter().stream().map(CultWrite::packet).toList();
    }

    private static boolean has(List<Object> writes, Class<?> type) {
        return writes.stream().anyMatch(type::isInstance);
    }

    private static AutoCloseable observeOnly(boolean observeOnly) throws ReflectiveOperationException {
        BaseConfigManager manager = CultAPI.INSTANCE.getConfigManager();
        Field field = BaseConfigManager.class.getDeclaredField("observeOnly");
        field.setAccessible(true);
        boolean original = field.getBoolean(manager);
        field.setBoolean(manager, observeOnly);
        return () -> field.setBoolean(manager, original);
    }
}
