package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static ac.cult.cultac.network.JavaTickFixture.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.runner.SimulationProcessor;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.math.Vector3dm;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.Friction;
import java.util.List;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Crouched clients on a ledge rim. Player#maybeBackOffFromEdge shortens only the axis whose 0.05-stepped move would
 * fall, so Entity#move hides deltaMovement on that axis alone, signed toward the drop; along the rim the client moves
 * exactly by its delta.
 */
class JavaSneakEdgeHiddenAxisTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput JUMP_FORWARD_SNEAK =
            new ServerboundPlayerInput(true, false, false, false, true, true, false);
    private static final ServerboundPlayerInput BACK_SNEAK =
            new ServerboundPlayerInput(false, true, false, false, false, true, false);
    private static final ServerboundPlayerInput FORWARD_RIGHT_SPRINT_SNEAK =
            new ServerboundPlayerInput(true, false, false, true, false, true, true);
    private static final ServerboundPlayerInput RIGHT_SPRINT_SNEAK =
            new ServerboundPlayerInput(false, false, false, true, false, true, true);
    private static final float SPEED = 0.1F;
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float CROUCHED = 0.3F;
    private static final double GROUND_Y = -0.0784000015258789;
    // The box overlaps the floor's east edge at x = 1 by 0.03.
    private static final double EAST_RIM_X = 1.27;

    /** A one-block bridge along z, walked crouched at the keys' speed with the box over either rim. */
    @ParameterizedTest
    @CsvSource({"1.27, V_26_3", "-0.27, V_26_3", "1.27, V_1_21_4", "-0.27, V_1_21_4"})
    void aCrouchedWalkAlongEitherRimOfABridgeIsExact(double rimX, ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, client)) {
            floor(f, (x, z) -> x == 0);
            Walker walker = new Walker(f, new Vec3(rimX, FLOOR_Y, -6.0), 0.0F);
            for (int tick = 0; tick < 25; tick++) walker.assertExact(FORWARD_SNEAK, CROUCHED);
        }
    }

    /**
     * Walking at the rim, shift pressed on that tick and held still for three ticks, then released: the edge holds the
     * position while deltaMovement keeps the walk toward the drop, and the release moves by it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"V_26_3", "V_1_21_4"})
    void aWalkStoppedAtTheRimByShiftReleasesItsVelocity(ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, client)) {
            floor(f, (x, z) -> z <= 0);
            Walker walker = new Walker(f, new Vec3(0.3, FLOOR_Y, 1.27), 0.0F);
            walker.velocity = new Vec3(0.0, GROUND_Y, 0.2);
            setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", false);
            // Shift is new this tick, so LocalPlayer#aiStep does not crouch yet.
            walker.assertExact(FORWARD_SNEAK, 1.0F);
            for (int tick = 0; tick < 3; tick++) walker.assertExact(SNEAK, CROUCHED);
            walker.assertExact(NONE, CROUCHED);
        }
    }

    /**
     * Backing crouched onto a north rim a few degrees off its normal, then releasing the keys: the drift along the rim
     * decays below LivingEntity#aiStep's 0.003 cutoff while the velocity the edge holds keeps the horizontal length
     * above it, so the drift carries on.
     */
    @ParameterizedTest
    @CsvSource({
        "4.0, false, V_26_3",
        "-4.0, false, V_26_3",
        "7.0, false, V_26_3",
        "-7.0, false, V_26_3",
        "7.0, true, V_1_21_11",
        "6.5, true, V_1_21_11"
    })
    void aSlowDriftAlongTheRimOutlivesTheMovementCutoff(float yaw, boolean sprintKey, ClientVersion client)
            throws Exception {
        ServerboundPlayerInput back = new ServerboundPlayerInput(false, true, false, false, false, true, sprintKey);
        ServerboundPlayerInput still = new ServerboundPlayerInput(false, false, false, false, false, true, sprintKey);
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, client)) {
            floor(f, (x, z) -> z >= 0);
            Walker walker = new Walker(f, new Vec3(0.5, FLOOR_Y, 0.6), yaw);
            for (int tick = 0; tick < 20; tick++) walker.assertExact(back, CROUCHED);
            assertTrue(walker.backsOff(back, CROUCHED, SPEED));
            for (int tick = 0; tick < 4; tick++) walker.assertExact(still, CROUCHED);
        }
    }

    /** Backing diagonally onto a platform's corner: the corner's diagonal probe shortens both axes. */
    @Test
    void aDiagonalBackwardWalkOntoACornerIsExact() throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0 && z <= 0);
            Walker walker = new Walker(f, new Vec3(0.6, FLOOR_Y, 0.6), 135.0F);
            for (int tick = 0; tick < 20; tick++) walker.assertExact(BACK_SNEAK, CROUCHED);
            walker.assertExact(NONE, CROUCHED);
        }
    }

    /**
     * Knocked toward the drop while crouched on the rim: the edge holds the whole knockback, LivingEntity#travel's
     * friction decays it once, and releasing shift moves by that. The knockback may arrive after a dozen ticks of
     * latency, and the floor may be slime.
     */
    @ParameterizedTest
    @CsvSource({"0, false", "12, false", "0, true"})
    void aKnockbackTowardTheDropIsReleasedAfterTheEdgeHeldIt(int pendingTicks, boolean slime) throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0);
            if (slime) {
                for (int z = -8; z <= 8; z++) block(f, 0, z, BlockIds.SLIME_BLOCK);
                settle(f);
            }
            Walker walker = new Walker(f, new Vec3(EAST_RIM_X, FLOOR_Y, -4.0), 0.0F);
            walker.assertExact(SNEAK, CROUCHED);
            int transaction = f.velocity(new Vec3(0.4, 0.0, 0.0));
            for (int tick = 0; tick < pendingTicks; tick++) walker.assertExact(SNEAK, CROUCHED);
            f.pongThrough(transaction);
            walker.velocity = new Vec3(0.4, 0.0, 0.0);
            walker.assertExact(SNEAK, CROUCHED);
            walker.assertExact(NONE, CROUCHED);
        }
    }

    /**
     * The same held knockback on 26.3 under a friction_modifier of 0.5: LivingEntity#travelInAir decays it by the
     * modified ground friction, and releasing shift moves by that.
     */
    @Test
    void aKnockbackHeldByTheEdgeDecaysByTheModifiedFriction() throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0);
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID,
                    List.of(new AttributeSnapshot(
                            "minecraft:friction_modifier",
                            1.0,
                            List.of(new AttributeModifier(
                                    "test:modifier", -0.5, AttributeModifier.Operation.ADD_VALUE)))));
            Walker walker = new Walker(f, new Vec3(EAST_RIM_X, FLOOR_Y, -4.0), 0.0F);
            walker.groundFriction = Friction.getGroundFriction(f.player, 0.6F);
            assertTrue(walker.groundFriction > 0.6F * 0.91F);
            walker.assertExact(SNEAK, CROUCHED);
            f.pongThrough(f.velocity(new Vec3(0.4, 0.0, 0.0)));
            walker.velocity = new Vec3(0.4, 0.0, 0.0);
            walker.assertExact(SNEAK, CROUCHED);
            walker.assertExact(NONE, CROUCHED);
        }
    }

    /** Walking crouched off the west rim of a platform, away from the drop, at the keys' speed. */
    @ParameterizedTest
    @ValueSource(strings = {"V_26_3", "V_1_21_4"})
    void aCrouchedWalkAwayFromTheDropIsExact(ClientVersion client) throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V26_3, client)) {
            floor(f, (x, z) -> x >= 0);
            Walker walker = new Walker(f, new Vec3(-0.27, FLOOR_Y, -4.0), -90.0F);
            for (int tick = 0; tick < 10; tick++) walker.assertExact(FORWARD_SNEAK, CROUCHED);
        }
    }

    /** The same knockback decaying by the ground friction while the client stays crouched on the rim. */
    @Test
    void aKnockbackAlongTheRimDecaysExactlyWhileCrouched() throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0);
            Walker walker = new Walker(f, new Vec3(EAST_RIM_X, FLOOR_Y, -6.0), 0.0F);
            walker.assertExact(SNEAK, CROUCHED);
            int transaction = f.velocity(new Vec3(0.0, 0.0, 0.4));
            for (int tick = 0; tick < 3; tick++) walker.assertExact(SNEAK, CROUCHED);
            f.pongThrough(transaction);
            walker.velocity = new Vec3(0.0, 0.0, 0.4);
            for (int tick = 0; tick < 6; tick++) walker.assertExact(SNEAK, CROUCHED);
            for (int tick = 0; tick < 6; tick++) walker.assertExact(FORWARD_SNEAK, CROUCHED);
        }
    }

    /**
     * Walking crouched into the rim, then jumping toward the drop: Player#maybeBackOffFromEdge leaves a rising delta
     * whole, so the jump moves by the velocity the edge held plus the input.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aJumpOffTheRimMovesByTheWholeDelta(boolean whole) throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0);
            Walker walker = new Walker(f, new Vec3(EAST_RIM_X, FLOOR_Y, -4.0), -90.0F);
            for (int tick = 0; tick < 8; tick++) walker.assertExact(FORWARD_SNEAK, CROUCHED);
            f.input(JUMP_FORWARD_SNEAK);
            Vec3 input = ClientMoveInput.of(f, JUMP_FORWARD_SNEAK, CROUCHED, SPEED, -90.0F);
            double dx = whole ? walker.velocity.x + input.x : 0.0;
            Vec3 to = walker.position.add(dx, (double) 0.42F, walker.velocity.z + input.z);
            PredictionResult jump = f.tick(to, false);
            assertEquals(whole, exact(jump), describe(jump));
        }
    }

    /**
     * Sprinting crouched on 26.3 diagonally into a rim: the tick Player#maybeBackOffFromEdge first shortens the axis
     * toward the drop, the delta it shortened still holds the keys' whole input, forward impulse included.
     */
    @ParameterizedTest
    @ValueSource(floats = {-90.0F, -100.0F, -110.0F, -130.0F})
    void aSprintingCrouchedDiagonalWalkIntoTheRimIsExact(float yaw) throws Exception {
        try (var f = new JavaTickFixture()) {
            floor(f, (x, z) -> x <= 0);
            Walker walker = new Walker(f, new Vec3(0.3, FLOOR_Y, -4.0), yaw);
            f.input(FORWARD_RIGHT_SPRINT_SNEAK);
            f.command(PlayerCommandAction.START_SPRINTING);
            boolean backedOff = false;
            for (int tick = 0; tick < 14; tick++) {
                backedOff |= walker.backsOff(FORWARD_RIGHT_SPRINT_SNEAK, CROUCHED, SPRINT_SPEED);
                walker.assertExact(FORWARD_RIGHT_SPRINT_SNEAK, CROUCHED, SPRINT_SPEED);
            }
            assertTrue(backedOff);
        }
    }

    /** One vanilla crouched client tick at a time: Player#maybeBackOffFromEdge, Entity#move, ground friction. */
    private static final class Walker {
        private final JavaTickFixture f;
        private final float yaw;
        private Vec3 position;
        private Vec3 velocity = new Vec3(0.0, GROUND_Y, 0.0);
        private ServerboundPlayerInput keys;
        private float groundFriction = Float.NaN;
        private Vec3 extra = Vec3.ZERO;

        Walker(JavaTickFixture f, Vec3 position, float yaw) throws Exception {
            this.f = f;
            this.position = position;
            this.yaw = yaw;
            f.player.xRot = f.player.lastTickXRot = yaw;
            f.player.yRot = 0.0F;
            f.place(position, velocity);
            setField(SimulationProcessor.class, f.player.checkManager.getSimulationProcessor(), "lastSneaking", true);
        }

        void assertExact(ServerboundPlayerInput keys, float movingSlowlyFactor) {
            assertExact(keys, movingSlowlyFactor, SPEED);
        }

        void assertExact(ServerboundPlayerInput keys, float movingSlowlyFactor, float speed) {
            PredictionResult result = step(keys, movingSlowlyFactor, speed);
            assertTrue(exact(result), describe(result) + " at " + position);
        }

        PredictionResult step(ServerboundPlayerInput keys, float movingSlowlyFactor, float speed) {
            if (!keys.equals(this.keys)) {
                f.input(keys);
                this.keys = keys;
            }
            Vec3 delta = delta(keys, movingSlowlyFactor, speed);
            Vec3 move = keys.shift() ? backOff(delta) : delta;
            // LivingEntity#travel reads the friction of the block under the position before the move.
            float friction = Float.isNaN(groundFriction) ? friction(f, position) * 0.91F : groundFriction;
            position = position.add(move.x, 0.0, move.z);
            PredictionResult result = f.tick(position, true);
            velocity = new Vec3(delta.x * friction, GROUND_Y, delta.z * friction);
            return result;
        }

        /** Whether Player#maybeBackOffFromEdge would shorten the next tick's delta for {@code keys}. */
        boolean backsOff(ServerboundPlayerInput keys, float movingSlowlyFactor, float speed) {
            Vec3 delta = delta(keys, movingSlowlyFactor, speed);
            Vec3 move = backOff(delta);
            return move.x != delta.x || move.z != delta.z;
        }

        private Vec3 delta(ServerboundPlayerInput keys, float movingSlowlyFactor, float speed) {
            Vec3 input = ClientMoveInput.of(f, keys, movingSlowlyFactor, speed, yaw);
            return new Vec3(velocity.x + input.x + extra.x, 0.0, velocity.z + input.z + extra.z);
        }

        private Vec3 backOff(Vec3 delta) {
            Vector3dm backedOff = Collisions.maybeBackOffFromEdge(
                    new Vector3dm(delta.x, 0.0, delta.z), f.player, true, 0.0, f.player.boundingBox);
            return new Vec3(backedOff.getX(), 0.0, backedOff.getZ());
        }
    }

    /** Block#getFriction of the floor block under {@code position}, or of the block the box overhangs from. */
    private static float friction(JavaTickFixture f, Vec3 position) {
        for (double dx : new double[] {0.0, -0.3, 0.3}) {
            for (double dz : new double[] {0.0, -0.3, 0.3}) {
                int state = f.player.compensatedWorld.getBlockStateIdAt(
                        (int) Math.floor(position.x + dx), FLOOR_Y - 1, (int) Math.floor(position.z + dz));
                if (BlockIds.is(state, BlockIds.SLIME_BLOCK)) return 0.8F;
                if (!BlockIds.is(state, BlockIds.AIR)) return 0.6F;
            }
        }
        return 0.6F;
    }

    /** Keeps the fixture floor only where {@code keep} holds. */
    private static void floor(JavaTickFixture f, BiPredicate<Integer, Integer> keep) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (!keep.test(x, z)) block(f, x, z, BlockIds.AIR);
            }
        }
        settle(f);
    }

    private static void block(JavaTickFixture f, int x, int z, BlockDefinition block) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y - 1, z), block.defaultState());
    }

    private static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }
}
