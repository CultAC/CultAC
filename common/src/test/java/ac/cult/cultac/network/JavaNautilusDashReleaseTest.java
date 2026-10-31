package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertVehicleExact;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.packetentity.PacketEntityNautilus;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client dashes a saddled nautilus under water. ClientLevel#tickNonPassenger finishes AbstractNautilus#tick,
 * whose tail runs the dash cooldown down, before tickPassenger runs the rider's LocalPlayer#aiStep. The rider's release
 * therefore reads the cooldown its own client tick left, and AbstractNautilus#tickRidden dashes on the next root tick.
 */
class JavaNautilusDashReleaseTest {
    private static final int VEHICLE = 100;
    /** AbstractNautilus#executeRidersJump's dash cooldown. */
    private static final int DASH_COOLDOWN = 40;

    private static final ServerboundPlayerInput IDLE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    /** The nautilus at rest a block over the floor, under water. */
    private static final Vec3 SEAT = new Vec3(0.5, FLOOR_Y + 1, 0.5);
    /**
     * AbstractNautilus#executeRidersJump in water for a tapped release:
     * PlayerRideableJumping#getPlayerJumpPendingScale's 0.4F, the controller looking along +z, movement_speed 1.0 and
     * water's block speed factor 1.0.
     */
    private static final double DASH = 1.2F * 0.4F;

    /**
     * The tail of the tick before the release leaves the cooldown at 1, and the release tick's own tail spends it
     * before the rider releases. The rider's charge was reset during the cooldown, which still dashes at 0.4F.
     */
    @Test
    void aReleaseOnTheFirstReadyTickDashesOnTheNextTick() throws Exception {
        try (var f = mounted()) {
            Drift drift = dashed(f);
            for (int tick = 2; tick < DASH_COOLDOWN; tick++) {
                assertVehicleExact(f.rideTick(drift.next(), false), tick);
            }
            assertEquals(1, nautilus(f).dashCooldown, "the tick before the release left the cooldown at 1");

            f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE);
            assertVehicleExact(f.rideTick(drift.next(), false), DASH_COOLDOWN);
            drift.dash();
            assertVehicleExact(f.rideTick(drift.next(), false), DASH_COOLDOWN + 1);
            assertVehicleExact(f.rideTick(drift.next(), false), DASH_COOLDOWN + 2);
        }
    }

    /** A tick earlier, the release tick's tail leaves the cooldown at 1 and AbstractNautilus#onPlayerJump drops it. */
    @Test
    void aReleaseBeforeTheCooldownIsSpentIsLost() throws Exception {
        try (var f = mounted()) {
            Drift drift = dashed(f);
            for (int tick = 2; tick < DASH_COOLDOWN - 1; tick++) {
                assertVehicleExact(f.rideTick(drift.next(), false), tick);
            }

            f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE);
            assertVehicleExact(f.rideTick(drift.next(), false), DASH_COOLDOWN - 1);
            assertEquals(0.0F, nautilus(f).pendingJumpScale, "a release with the cooldown at 1 armed a dash");
            assertVehicleExact(f.rideTick(drift.next(), false), DASH_COOLDOWN);
        }
    }

    /**
     * A second release sent on the dash tick reads the cooldown the dash just set, so the next tick does not dash
     * again. LocalPlayer#aiStep would not even send it.
     */
    @Test
    void aReleaseOnTheDashTickIsLost() throws Exception {
        try (var f = mounted()) {
            Drift drift = new Drift();
            f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE);
            assertVehicleExact(f.rideTick(drift.next(), false), 0);

            f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE);
            drift.dash();
            assertVehicleExact(f.rideTick(drift.next(), false), 1);
            assertEquals(0.0F, nautilus(f).pendingJumpScale, "a release on the dash tick armed a second dash");
            assertVehicleExact(f.rideTick(drift.next(), false), 2);
        }
    }

    /** A tapped release on tick 0 and the dash it arms on tick 1; the drift after the dash. */
    private static Drift dashed(JavaTickFixture f) {
        Drift drift = new Drift();
        f.command(PlayerCommandAction.START_JUMPING_WITH_HORSE);
        assertVehicleExact(f.rideTick(drift.next(), false), 0);
        drift.dash();
        assertVehicleExact(f.rideTick(drift.next(), false), 1);
        assertEquals(DASH_COOLDOWN - 1, nautilus(f).dashCooldown, "the dash tick's tail did not run its cooldown");
        return drift;
    }

    /** AbstractNautilus#travelInWater with no ridden input: no gravity, the move, then the 0.9 drag. */
    private static final class Drift {
        private double z = SEAT.z;
        private double velocity;

        /** AbstractNautilus#tickRidden adds the dash before the tick's move. */
        void dash() {
            velocity += DASH;
        }

        /** The tick's end position. */
        Vec3 next() {
            z += velocity;
            velocity *= 0.9;
            return new Vec3(SEAT.x, SEAT.y, z);
        }
    }

    /** The player seated on a saddled nautilus at rest in deep water, looking along +z with no keys held. */
    private static JavaTickFixture mounted() throws Exception {
        var f = new JavaTickFixture();
        f.water(new BlockPos(-2, FLOOR_Y, -2), new BlockPos(2, FLOOR_Y + 3, 9));
        f.player.xRot = f.player.lastTickXRot = 0.0F;
        f.player.yRot = f.player.lastTickYRot = 0.0F;
        f.place(SEAT.add(0, 1, 0), Vec3.ZERO);
        f.player.compensatedEntities.addEntity(
                VEHICLE, EntityTypes.defaults().byKey("minecraft:nautilus").id(), SEAT, 0, 0, 0);
        PacketEntityNautilus nautilus = nautilus(f);
        nautilus.hasSaddle = true;
        nautilus.isTame = true;
        f.setPassengers(VEHICLE, List.of(f.player.entityID));
        f.pongThrough(f.player.lastTransactionSent.get());
        f.input(IDLE);
        f.player.boatData.useNextInputForVehicleTick();
        f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
        return f;
    }

    private static PacketEntityNautilus nautilus(JavaTickFixture f) {
        return (PacketEntityNautilus) f.player.compensatedEntities.getEntity(VEHICLE);
    }
}
