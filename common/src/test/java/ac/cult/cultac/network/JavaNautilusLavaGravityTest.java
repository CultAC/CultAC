package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client steers a saddled nautilus with Slow Falling straight up through deep lava. AbstractNautilus overrides
 * only travelInWater, so lava runs LivingEntity#travelInFluid, which samples isFalling and getEffectiveGravity from the
 * deltaMovement it starts with, before travelInLava's moveRelative adds the ridden input's vertical component.
 */
class JavaNautilusLavaGravityTest {
    private static final int VEHICLE = 100;
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    // AbstractNautilus#getRiddenInput looking straight up is (0, 1, 0); travelInLava's moveRelative scales it by 0.02F.
    private static final double STEER_Y = 0.02F;
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);

    /**
     * Each tick starts falling or rising on the carried deltaMovement alone: the first starts at rest, so it falls at
     * Slow Falling's 0.01 although its steering moves it up.
     */
    @Test
    void steeringUpDoesNotDecideTheSlowFallingGravity() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -2; x <= 2; x++)
                for (int y = FLOOR_Y; y <= FLOOR_Y + 4; y++)
                    for (int z = -2; z <= 2; z++)
                        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.LAVA.defaultState());
            mount(f);
            Vec3 at = SEAT;
            double y = 0;
            for (int tick = 0; tick < 6; tick++) {
                double gravity = y <= 0.0 ? Math.min(0.08, 0.01) : 0.08;
                double moved = y + STEER_Y;
                at = at.add(0, moved, 0);
                assertVehicleExact(f.rideTick(at, false), tick);
                // travelInLava: deep lava's 0.5 drag, then gravity / 4; LivingEntity#aiStep's threshold next tick.
                y = moved * 0.5 + -gravity / 4.0;
                if (Math.abs(y) < 0.003) y = 0;
            }
        }
    }

    /** Seats the player on a saddled, slow-falling nautilus at rest, holding forward and looking straight up. */
    private static void mount(JavaTickFixture f) throws Exception {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.player.xRot = f.player.lastTickXRot = 0.0F;
        f.player.yRot = f.player.lastTickYRot = -90.0F;
        f.place(SEAT.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), SEAT, 0, 0, 0);
        var nautilus = (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
        nautilus.hasSaddle = true;
        nautilus.isTame = true;
        nautilus.addPotionEffect(MovementEffect.SLOW_FALLING, 0);
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(FORWARD);
        f.holdRiderInput();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
    }
}
