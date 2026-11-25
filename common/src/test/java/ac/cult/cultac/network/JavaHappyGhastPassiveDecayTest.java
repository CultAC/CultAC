package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * HappyGhast#getControllingPassenger names no player for a ghast without a harness, so its rider's client cannot
 * simulate its movement. On every release with happy ghasts, LivingEntity#aiStep then scales its delta movement by
 * 0.98 each tick it is not interpolating.
 */
class JavaHappyGhastPassiveDecayTest {
    private static final int GHAST = 7120;
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 CARRIED = new Vec3(0.2, -0.1, 0.05);

    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_6", "V_26_2", "V_26_3"})
    void anUnharnessedGhastsVelocityDecaysEachTick(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, version)) {
            f.place(START, Vec3.ZERO);
            f.teleport(START, new RelativeFlag(0));
            f.player.compensatedEntities.addEntity(GHAST, EntityTypeIds.HAPPY_GHAST, START, 0, 0, 0);
            f.setPassengers(GHAST, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            PacketEntity ghast = f.player.compensatedEntities.getEntity(GHAST);
            assertFalse(f.player.compensatedEntities.vehicles.canClientAuthoritativelyMoveVisibleRoot(ghast));
            assertTrue(f.player.compensatedEntities.vehicles.applyClientboundVehicleVelocity(ghast, CARRIED));

            Vec3 expected = CARRIED;
            for (int tick = 1; tick <= 3; tick++) {
                // The passive rider's tick: LocalPlayer#tick sends only its Rot.
                f.rideTick();
                expected = expected.multiply(0.98D, 0.98D, 0.98D);
                assertEquals(expected, ghast.deltaMovement, "tick " + tick);
                assertEquals(
                        List.of(expected),
                        List.copyOf(f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels()),
                        "tick " + tick);
            }
        }
    }
}
