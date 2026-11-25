package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.stage.VelocityTransformer;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A player walks past JavaTickFixture's west floor edge at x = -8 and presses shift. On the press tick
 * Player#maybeBackOffFromEdge zeroes the delta of a player still on the ground whose box hangs past the edge, and the
 * player drops while deltaMovement keeps the delta. On the next tick the player is off the ground and
 * Player#isAboveGround finds nothing within maxUpStep less the fall distance, so the delta moves whole with the
 * crouching input LocalPlayer#aiStep now applies.
 */
class JavaSneakEdgeFallTest {
    private static final ServerboundPlayerInput WALK = keys(false, false, false);
    private static final ServerboundPlayerInput WALK_RIGHT = keys(true, false, false);
    private static final ServerboundPlayerInput SNEAK_WALK = keys(false, false, true);
    private static final ServerboundPlayerInput SNEAK_WALK_RIGHT = keys(true, false, true);
    private static final ServerboundPlayerInput SNEAK_JUMP = keys(false, true, true);
    // Forward moves toward -x and +z; forward and right together move toward -x and -z.
    private static final float YAW = 67.5F;
    private static final double EDGE_X = -8.0;
    private static final double START_X = -8.2;
    private static final double GROUNDED_Y = -0.0784000015258789;

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void theFirstFallingTickAfterThePressMovesWhole(ClientVersion version) throws Exception {
        assertExact(version, WALK, SNEAK_WALK, SNEAK_WALK_RIGHT, SNEAK_WALK_RIGHT);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void aFallPastTheCrouchingInputIsFlagged(ClientVersion version) throws Exception {
        try (var f = fixture(version)) {
            Client client = new Client(f);
            client.tick(WALK, 0.0);
            client.tick(SNEAK_WALK, 0.0);
            PredictionResult fall = client.tick(SNEAK_WALK_RIGHT, -0.01);
            assertFalse(exact(fall), describe(fall));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void shiftPressedOneTickEarlierHoldsAtTheEdge(ClientVersion version) throws Exception {
        assertExact(version, SNEAK_WALK, SNEAK_WALK, SNEAK_WALK_RIGHT, SNEAK_WALK_RIGHT);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void shiftPressedOnTheFirstFallingTickMovesWhole(ClientVersion version) throws Exception {
        assertExact(version, WALK, WALK, SNEAK_WALK_RIGHT, SNEAK_WALK_RIGHT);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void aJumpOnThePressTickMovesWhole(ClientVersion version) throws Exception {
        assertExact(version, WALK, SNEAK_JUMP, SNEAK_WALK_RIGHT, SNEAK_WALK_RIGHT);
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void shiftReleasedOnTheFirstFallingTickMovesWhole(ClientVersion version) throws Exception {
        assertExact(version, WALK, SNEAK_WALK, WALK_RIGHT, WALK_RIGHT);
    }

    private static void assertExact(ClientVersion version, ServerboundPlayerInput... ticks) throws Exception {
        try (var f = fixture(version)) {
            Client client = new Client(f);
            for (int tick = 0; tick < ticks.length; tick++) {
                PredictionResult result = client.tick(ticks[tick], 0.0);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
            }
        }
    }

    private static JavaTickFixture fixture(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.player.xRot = YAW;
        return f;
    }

    private static ServerboundPlayerInput keys(boolean right, boolean jump, boolean shift) {
        return new ServerboundPlayerInput(true, false, false, right, jump, shift, false);
    }

    /** A vanilla client walking at the floor's west edge, its box 0.6 wide; only that edge is near. */
    private static final class Client {
        private final JavaTickFixture f;
        private double x = START_X;
        private double y = FLOOR_Y;
        private double z = 0.5;
        private double velocityX;
        private double velocityY = GROUNDED_Y;
        private double velocityZ;
        private double fallDistance;
        private boolean onGround = true;
        private boolean lastShift;
        private ServerboundPlayerInput keys;

        Client(JavaTickFixture f) {
            this.f = f;
            // The deltaMovement of a player that walked forward at the walking speed.
            Vec3 input = input(WALK, 1.0F, 0.1F);
            velocityX = input.x;
            velocityZ = input.z;
            f.place(new Vec3(x, y, z), new Vec3(velocityX, velocityY, velocityZ));
        }

        /** One client tick of LocalPlayer#aiStep and LivingEntity#travelInAir; {@code past} adds to the sent x. */
        PredictionResult tick(ServerboundPlayerInput keys, double past) {
            if (keys != this.keys) f.input(keys);
            this.keys = keys;
            // LocalPlayer#aiStep crouches by the shift key of the previous tick's input.
            Vec3 input = input(keys, lastShift ? 0.3F : 1.0F, onGround ? 0.1F : 0.02F);
            lastShift = keys.shift();
            // LivingEntity#jumpFromGround.
            double deltaY = keys.jump() && onGround ? (double) 0.42F : velocityY;
            double deltaX = velocityX + input.x;
            double deltaZ = velocityZ + input.z;
            double movedX = deltaX;
            double movedZ = deltaZ;
            if (keys.shift() && !(deltaY > 0.0) && aboveGround()) {
                movedX = backOff(deltaX);
                // Nothing near on z: the probe falls exactly when the box's current column does.
                if (canFallAtLeast(0.0, 0.6)) movedZ = 0.0;
                if (movedX != 0.0 && movedZ != 0.0 && canFallAtLeast(movedX, 0.6)) movedX = movedZ = 0.0;
            }
            // Entity#collide clips y first against the floor under the box where the tick starts.
            boolean overFloor = x + 0.3 > EDGE_X;
            double movedY = overFloor && y + deltaY < FLOOR_Y ? FLOOR_Y - y : deltaY;
            boolean wasOnGround = onGround;
            onGround = movedY != deltaY && deltaY < 0.0;
            x += movedX;
            y += movedY;
            z += movedZ;
            fallDistance = onGround ? 0.0 : movedY < 0.0 ? fallDistance - movedY : fallDistance;
            float friction = wasOnGround ? 0.6F * 0.91F : 0.91F;
            velocityX = deltaX * friction;
            velocityZ = deltaZ * friction;
            velocityY = ((onGround ? 0.0 : deltaY) - 0.08) * (double) 0.98F;
            // LocalPlayer#sendPosition sends no position for a tick that moved 2.0E-4 or less.
            if (movedX * movedX + movedY * movedY + movedZ * movedZ <= 4.0E-8 && past == 0.0) {
                f.tickEnd();
                return f.player.checkManager.getSimulationProcessor().getLastPrediction();
            }
            return f.tick(new Vec3(x + past, y, z), onGround);
        }

        /** Player#isAboveGround. */
        private boolean aboveGround() {
            return onGround || fallDistance < 0.6 && !canFallAtLeast(0.0, 0.6 - fallDistance);
        }

        /** Player#maybeBackOffFromEdge's x loop. */
        private double backOff(double deltaX) {
            double stepX = Math.signum(deltaX) * 0.05;
            while (deltaX != 0.0 && canFallAtLeast(deltaX, 0.6)) {
                if (Math.abs(deltaX) <= 0.05) return 0.0;
                deltaX -= stepX;
            }
            return deltaX;
        }

        /** Player#canFallAtLeast against the floor block below y = FLOOR_Y. */
        private boolean canFallAtLeast(double deltaX, double minHeight) {
            boolean overFloor = x + 0.3 - 1.0E-7 + deltaX > EDGE_X;
            return !(overFloor && y - minHeight - 1.0E-7 < FLOOR_Y && y > FLOOR_Y - 1);
        }

        /** KeyboardInput#tick, LocalPlayer#modifyInput and Entity#getInputVector at {@code speed}. */
        private Vec3 input(ServerboundPlayerInput keys, float movingSlowlyFactor, float speed) {
            float strafe = keys.left() == keys.right() ? 0.0F : keys.left() ? 1.0F : -1.0F;
            float forward = keys.forward() == keys.backward() ? 0.0F : keys.forward() ? 1.0F : -1.0F;
            Vec3 modified = VelocityTransformer.getExactControllerModifiedInputStatic(
                    new Vec3(strafe, 0.0D, forward), 1.0F, movingSlowlyFactor);
            Vec3 movement = (modified.lengthSqr() > 1.0D ? modified.normalize() : modified).scale(speed);
            float yawRadians = f.player.xRot * ((float) Math.PI / 180F);
            float sin = f.player.trigHandler.sin(yawRadians);
            float cos = f.player.trigHandler.cos(yawRadians);
            return new Vec3(movement.x * cos - movement.z * sin, 0.0D, movement.z * cos + movement.x * sin);
        }
    }
}
