package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ClientLevel#tickNonPassenger ticks a ridden horse before its rider, so AbstractHorse#getRiddenInput reads the xxa the
 * rider's last LocalPlayer#applyInput stored. On the tick the server mounts a player who was sneaking on foot, that is
 * still the sneak-scaled input of its last on-foot tick; a rider cannot crouch, so the next tick stores it unscaled.
 */
class JavaSneakingMountInputTest {
    private static final int HORSE = 100;
    private static final float SPEED = 0.2249995F;
    private static final double GRAVITY = -0.0784000015258789;

    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theFirstRiddenTickReadsTheInputOfTheLastTickOnFoot(boolean sneaking) throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = JavaTickFixture.observeOnly()) {
            Vec3 feet = new Vec3(0.5, FLOOR_Y, 0.5);
            Vec3 start = new Vec3(4.5, FLOOR_Y + 3, 0.5);
            f.place(feet, new Vec3(0, GRAVITY, 0));
            f.player.onGround = f.player.lastOnGround = true;
            f.input(NONE);
            f.player.compensatedEntities.addEntity(HORSE, EntityTypeIds.HORSE, start, 0, 0, 0);
            PacketEntityHorse horse = (PacketEntityHorse) f.player.compensatedEntities.getEntity(HORSE);
            horse.hasSaddle = true;
            horse.isTame = true;
            horse.movementSpeedAttribute = SPEED;
            // The spawned horse's interpolation target settles before it is ridden.
            for (int tick = 0; tick < 3; tick++) f.tick(feet, true);

            // The last ticks on foot hold left, sneaking or not; where they end is not this test's subject.
            // LocalPlayer#aiStep crouches by the shift key of the tick before, so the second of them is slowed.
            f.input(new ServerboundPlayerInput(false, false, true, false, false, sneaking, false));
            f.tick(feet, true);
            f.tick(feet, true);

            f.setPassengers(HORSE, List.of(f.player.entityID));
            f.pongThrough(f.player.lastTransactionSent.get());
            horse.onGround = false;
            horse.deltaMovement = Vec3.ZERO;
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.FALSE);

            // LocalPlayer#modifyInput's pair, AbstractHorse#getRiddenInput's half strafe and, for a root at rest in the
            // air, LivingEntity#getFlyingSpeed's tenth of the ridden speed.
            float xxa = (float)
                    VelocityTransformer.getExactControllerModifiedInputStatic(new Vec3(1, 0, 0), sneaking ? 0.3F : 1.0F)
                            .x;
            double strafe = (double) (xxa * 0.5F) * (double) (SPEED * 0.1F);
            Vec3 first = start.add(strafe, 0, 0);
            PredictionResult mounted = f.rideTick(first, false);
            assertExact(mounted, "the mount tick");
        }
    }

    private static void assertExact(PredictionResult result, String tick) {
        double offset =
                result.getTarget().subtract(result.getAcceptedClosestToTarget()).length();
        assertTrue(offset < 1.0E-7, tick + ": vehicle offset " + offset + " " + describe(result));
    }
}
