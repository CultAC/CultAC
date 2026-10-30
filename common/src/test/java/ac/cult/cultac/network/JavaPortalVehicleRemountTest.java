package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A strider ridden through a nether portal. Entity#teleportCrossDimension ejects the player, teleports it with a
 * ClientboundRespawnPacket, creates the strider's copy in the new level and seats the player on it in the same server
 * tick; the copy's pairing sends its add_entity and set_passengers before the client can answer the respawn. The
 * respawn's receipt must not clear that newer mount from the server's vehicle record.
 */
class JavaPortalVehicleRemountTest {
    private static final int OLD_STRIDER = 7700;
    private static final int NEW_STRIDER = 7803;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);

    @Test
    void aMountSentAfterTheRespawnSurvivesItsReceipt() throws Exception {
        try (var f = fixture()) {
            mount(f, OLD_STRIDER);
            DeadPlayerSetbackTest.respawn(
                    f,
                    ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA,
                    "minecraft:the_nether");
            addStrider(f, NEW_STRIDER);
            f.setPassengers(NEW_STRIDER, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());

            var vehicles = f.player.compensatedEntities.vehicles;
            assertEquals(NEW_STRIDER, vehicles.serverPlayerVehicle);
            assertTrue(vehicles.isServerPlayerPassengerOf(NEW_STRIDER));
            assertTrue(vehicles.hasClientObservedServerVehicle());
        }
    }

    @Test
    void aRespawnWithNoLaterMountStillClearsTheVehicle() throws Exception {
        try (var f = fixture()) {
            mount(f, OLD_STRIDER);
            DeadPlayerSetbackTest.respawn(
                    f,
                    ClientboundRespawn.KEEP_ATTRIBUTE_MODIFIERS | ClientboundRespawn.KEEP_ENTITY_DATA,
                    "minecraft:the_nether");
            f.pongThrough(f.player.lastTransactionSent.get());

            assertNull(f.player.compensatedEntities.vehicles.serverPlayerVehicle);
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        f.player.nettyScheduler.removeScheduler();
        Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
        f.place(START, Vec3.ZERO);
        return f;
    }

    private static void mount(JavaTickFixture f, int strider) {
        addStrider(f, strider);
        f.setPassengers(strider, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        assertEquals(strider, f.player.compensatedEntities.vehicles.serverPlayerVehicle);
    }

    private static void addStrider(JavaTickFixture f, int id) {
        var add = new ClientboundAddEntity(id, "minecraft:strider", new Vec3d(START.x, START.y, START.z), 0, 0, 0);
        var event =
                new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.ADD_ENTITY, add, false);
        f.player.packetEntityReplication.onAddEntity(event, f.player, add);
        event.tasksAfter().forEach(Runnable::run);
    }
}
