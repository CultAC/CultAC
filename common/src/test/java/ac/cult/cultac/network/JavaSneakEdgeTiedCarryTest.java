package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A shift-holding player walking off a floor's corner while the client may or may not still hold minecraft:sprinting.
 * Player#maybeBackOffFromEdge takes the whole move back on X and Z, so the report shows neither LivingEntity#getSpeed,
 * and deltaMovement keeps the input of whichever one the client moved with. On the tick shift is released
 * LocalPlayer#aiStep still crouches from the previous keys, the edge no longer holds the player, and the move is that
 * hidden velocity plus the slowed input.
 */
class JavaSneakEdgeTiedCarryTest {
    // A yaw whose sine and cosine differ between Mth's table and the fast-math one.
    private static final float YAW = 87.76156F;
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float SNEAKING = 0.3F;
    private static final float WALKING_SPEED = 0.1F;
    private static final float SPRINTING_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @ParameterizedTest
    @ValueSource(floats = {WALKING_SPEED, SPRINTING_SPEED})
    void theReleaseMovesWithTheVelocityOfTheSpeedTheClientHeld(float speed) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 edge = holdAtTheCorner(f);
            f.input(FORWARD);
            Vec3 hidden =
                    ClientMoveInput.of(f, FORWARD_SHIFT, SNEAKING, speed, YAW).scale(GROUND_FRICTION);
            Vec3 move = hidden.add(ClientMoveInput.of(f, FORWARD, SNEAKING, speed, YAW));
            PredictionResult release = f.tick(edge.add(move), true);
            assertTrue(exact(release), describe(release));
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.75, 1.25})
    void aReleaseOutsideBothSpeedsVelocitiesIsRejected(double scale) throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 edge = holdAtTheCorner(f);
            f.input(FORWARD);
            float speed = scale < 1.0 ? WALKING_SPEED : SPRINTING_SPEED;
            Vec3 hidden =
                    ClientMoveInput.of(f, FORWARD_SHIFT, SNEAKING, speed, YAW).scale(GROUND_FRICTION);
            Vec3 move = hidden.scale(scale).add(ClientMoveInput.of(f, FORWARD, SNEAKING, speed, YAW));
            PredictionResult release = f.tick(edge.add(move), true);
            assertFalse(exact(release), describe(release));
        }
    }

    /** Holds the player one tick against a corner both of whose edges the forward key walks off; returns it. */
    private static Vec3 holdAtTheCorner(JavaTickFixture f) {
        // The floor is the quadrant x >= 1, z < 0, and the player's box overlaps its corner by 1.0E-6 on each axis.
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (x < 1 || z >= 0) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), BlockIds.AIR.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        Vec3 edge = new Vec3(0.700001, FLOOR_Y, 0.299999);
        f.player.xRot = YAW;
        f.place(edge, Vec3.ZERO);
        f.input(SHIFT);
        assertTrue(exact(f.tick(edge, true)), "settling at the corner");

        // Self entity data cleared the sprint flag and no attribute update followed: the client holds the modifier
        // unless an unannounced LivingEntity#setSprinting(false) removed it.
        f.player.sprintState.onServerModifier(CompensatedEntities.SPRINTING_SPEED_MODIFIER);
        f.player.sprintState.onModifierPossiblyCleared();
        f.input(FORWARD_SHIFT);
        PredictionResult held = f.tick(edge, true);
        assertTrue(exact(held), "held at the corner: " + describe(held));
        return edge;
    }
}
