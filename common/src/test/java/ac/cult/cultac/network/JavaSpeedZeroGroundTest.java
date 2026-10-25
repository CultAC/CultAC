package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sprinting 26.3 client holding forward under Slowness VII. AttributeInstance#calculateValue clamps its
 * movement_speed to the RangedAttribute minimum of 0, and Entity#getInputVector scales the input by that speed, so
 * the client slides on the velocity of its last sprint jump.
 */
class JavaSpeedZeroGroundTest {
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final double FRICTION = 0.6F * 0.91F;
    private static final double SLOWNESS_VII = (double) -0.15F * 7;
    private static final float YAW = -81.15678F;
    private static final Vec3 START = new Vec3(150.6148306398367, FLOOR_Y, 154.50376870225557);
    private static final Vec3 VELOCITY = new Vec3(0.0604010380067536, -0.0784000015258789, 0.009362543478269269);

    @Test
    void theCapturedSlideAtSpeedZeroIsExact() throws Exception {
        try (var f = slidingAtSpeedZero()) {
            double x = START.x;
            double z = START.z;
            double vx = VELOCITY.x;
            double vz = VELOCITY.z;
            for (int tick = 0; tick < 4; tick++) {
                x += vx;
                z += vz;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                vx *= FRICTION;
                vz *= FRICTION;
            }
        }
    }

    @Test
    void aSlidePastTheInputFreeMoveAtSpeedZeroFlags() throws Exception {
        try (var f = slidingAtSpeedZero()) {
            double x = START.x;
            double z = START.z;
            double vx = VELOCITY.x;
            double vz = VELOCITY.z;
            for (int tick = 0; tick < 4; tick++) {
                x += vx + 0.0007;
                z += vz + 0.0007;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                assertRejected(result, "Speed");
                vx = (vx + 0.0007) * FRICTION;
                vz = (vz + 0.0007) * FRICTION;
            }
        }
    }

    private static JavaTickFixture slidingAtSpeedZero() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.ensureValidationChunkLoaded(9, 9);
        for (int x = 146; x <= 155; x++) {
            for (int z = 150; z <= 159; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.compensatedEntities.updateAttributes(
                f.player.entityID,
                List.of(new AttributeSnapshot(
                        "minecraft:movement_speed",
                        0.10000000149011612,
                        List.of(new AttributeModifier(
                                "minecraft:effect.slowness",
                                SLOWNESS_VII,
                                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)))));
        f.player.xRot = YAW;
        f.place(START, VELOCITY);
        f.player.isSprinting = true;
        f.player.sprintState.onClientTick(true);
        f.input(SPRINT_FORWARD);
        return f;
    }
}
