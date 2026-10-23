package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A grounded player holding jump takes a velocity whose Y is just below the gap to a ceiling.
 * LivingEntity#jumpFromGround raises it to the jump power, Entity#move clips that at the ceiling and
 * Entity#restituteMovementAfterCollisions zeroes Y, so the next tick falls from rest. This guards which equally
 * flagged candidate the jump tick carries.
 */
class KnockbackJumpUnderCeilingTest {
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 0.5);
    private static final Vec3 KNOCKBACK = new Vec3(-1.013245437343588, 0.19996337667093922, 1.135933589696637);
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void theJumpThatHitsTheCeilingIsTheCarriedMove() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 2, z), BlockIds.STONE.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(START, Vec3.ZERO);
            f.input(JUMP);
            f.pongThrough(f.velocity(KNOCKBACK));

            double ceilingGap = 2.0 - (double) 1.8F;
            Vec3 clipped = new Vec3(START.x + KNOCKBACK.x, FLOOR_Y + ceilingGap, START.z + KNOCKBACK.z);
            PredictionResult jump = f.tick(clipped, false);
            String jumped = describe(jump);
            PredictionResult fall = f.tick(
                    new Vec3(
                            clipped.x + KNOCKBACK.x * GROUND_FRICTION,
                            clipped.y - 0.0784000015258789,
                            clipped.z + KNOCKBACK.z * GROUND_FRICTION),
                    false);
            String fell = describe(fall);
            assertTrue(exact(jump) && exact(fall), "jump into the ceiling: " + jumped + "; fall: " + fell);
        }
    }
}
