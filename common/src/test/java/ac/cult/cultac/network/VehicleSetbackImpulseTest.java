package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.platform.api.world.PlatformWorld;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A knockback the player takes on foot moves the player, not the horse it is then seated on: Entity#rideTick zeroes a
 * passenger's delta movement every ride tick, and ClientPacketListener#handleSetEntityMotion moves only the entity the
 * packet names. A setback of the horse must not hand the horse the player's knockback.
 */
class VehicleSetbackImpulseTest {
    private static final int HORSE = 100;
    private static final Vec3 STANDING = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 GROUNDED = new Vec3(0, -0.0784000015258789, 0);
    private static final Vec3 KNOCKBACK = new Vec3(0.4, 0.36, 0);
    // AbstractHorse#getPassengerAttachmentPoint for an adult horse with no rider offset: 1.52 up.
    private static final Vec3 SEAT = STANDING.add(0, 1.52, 0);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aHorseSetbackCarriesNoKnockbackOfItsRider() throws Exception {
        try (var f = new JavaTickFixture()) {
            Mockito.when(f.fixture.user.getPlayer().getWorld()).thenReturn(Mockito.mock(PlatformWorld.class));
            f.place(STANDING, GROUNDED);
            f.playerPosition(STANDING, Vec3.ZERO, Set.of());
            f.pongThrough(f.player.sendTrailingTransaction());
            f.input(NONE);
            f.tick(STANDING, true);
            f.tick(STANDING, true);

            // A knockback, then ServerPlayer#startRiding seats the player on a horse. The client has handled neither.
            f.velocity(KNOCKBACK);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, STANDING, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.onGround = true;
            f.startRiding(HORSE, 50, SEAT, STANDING, 0.0F, 0.0F);

            // A setback that ticks nothing, such as a timer's, moves the horse.
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            teleports.executeNonSimulatingSetback();
            SetBackData setback = teleports.getRequiredSetBack();
            assertNotNull(setback);
            assertTrue(teleports.isPendingSetback(), "no setback is pending");
            Vec3 velocity = setback.getVelocity() == null ? Vec3.ZERO : setback.getVelocity();
            assertEquals(0.0, velocity.x, 1.0E-9, "the horse correction carries its rider's knockback: " + velocity);
            assertEquals(0.0, velocity.y, 1.0E-9, "the horse correction carries its rider's knockback: " + velocity);
            assertEquals(0.0, velocity.z, 1.0E-9, "the horse correction carries its rider's knockback: " + velocity);
        }
    }
}
