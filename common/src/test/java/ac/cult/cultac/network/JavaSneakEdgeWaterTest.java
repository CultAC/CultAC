package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client sprint-swimming on the floor of a still pool, holding shift against the floor's edge, where
 * Player#maybeBackOffFromEdge cuts the move toward the drop while deltaMovement keeps it, then releasing shift. The
 * moves are a real client's, translated onto the fixture floor; the drop starts at z = 1. A crouched player in water
 * flowing over an edge keeps the current the same way.
 */
class JavaSneakEdgeWaterTest {
    private static final ServerboundPlayerInput HOLD_EDGE =
            new ServerboundPlayerInput(true, false, true, false, false, true, true);
    private static final ServerboundPlayerInput RELEASE_AND_JUMP =
            new ServerboundPlayerInput(true, false, true, false, true, false, true);
    private static final ServerboundPlayerInput SHIFT =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_IN_WATER =
            new ServerboundPlayerInput(true, false, true, false, false, true, false);
    private static final ServerboundPlayerInput WADE =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.299.
    private static final double EDGE_X = 9.299;

    @Test
    void theVelocityTheEdgeHidesIsReleasedWithShift() throws Exception {
        try (var f = new JavaTickFixture()) {
            pool(f);
            f.place(recorded(218.76926011023812, 61.0, 190.71202434941054), new Vec3(
                    -0.005253295755957766, 0.0, -0.09200594186772264));
            rotate(f, -132.16786193847656F, 20.816083908081055F);
            f.input(HOLD_EDGE);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction().onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            // The edge shortens the move toward the drop; the move along the edge shows the keys.
            assertExact(f, -132.16786193847656F, 22.473060607910156F, 218.7649958282314, 61.0, 190.70004288072602, true);
            assertExact(f, -132.31849670410156F, 23.075597763061523F, 218.7620952764892, 61.0, 190.70004288072602, true);
            assertExact(f, -132.31849670410156F, 23.075597763061523F, 218.7604220819525, 61.0, 190.70004288072602, true);
            assertExact(f, -132.46913146972656F, 23.226232528686523F, 218.75979987617052, 61.0, 190.70004288072602, true);

            f.input(RELEASE_AND_JUMP);
            assertExact(f, -133.07167053222656F, 22.623695373535156F, 218.7599147142048, 61.00390634324402, 190.55785464501272, false);
            f.input(FORWARD_JUMP);
            assertExact(f, -136.68690490722656F, 16.74895477294922F, 218.7734634929508, 61.01887101063653, 190.41562408045826, false);
            assertExact(f, -139.54896545410156F, 9.669137954711914F, 218.79837399643162, 61.057648142321376, 190.27270188513194, false);
        }
    }

    /**
     * The same pool edge with the sprint flag never set, so Entity#updateSwimming cannot swim the player and
     * LocalPlayer#aiStep crouches it: an un-crouched input that only the full scale reproduces carries nothing, and
     * releasing shift after it moves on the crouched carry.
     */
    @Test
    void aCrouchingPlayerThatCannotSwimCarriesNoUncrouchedInput() throws Exception {
        assertTrue(exact(releaseAfterEdge(0.3F)));
        PredictionResult forbidden = releaseAfterEdge(1.0F);
        assertFalse(forbidden.getFlags().isEmpty(), "moved on the un-crouched carry: " + describe(forbidden));
    }

    /** Three ticks against the edge at {@code scale} once crouched, then the release moving on their carry. */
    private static PredictionResult releaseAfterEdge(float scale) throws Exception {
        try (var f = new JavaTickFixture()) {
            pool(f);
            float yaw = -132.16786193847656F;
            rotate(f, yaw, 20.0F);
            Vec3 position = recorded(218.7649958282314, 61.0, 190.70004288072602);
            Vec3 velocity = new Vec3(-0.004, 0.0, -0.08);
            f.place(position, velocity);
            f.input(SNEAK_IN_WATER);
            for (int tick = 0; tick < 3; tick++) {
                // travelInWater's moveRelative(0.02F); shift is new on the first tick, so the crouch starts after it.
                Vec3 delta = velocity.add(ClientMoveInput.of(f, SNEAK_IN_WATER, tick == 0 ? 1.0F : scale, 0.02F, yaw));
                position = position.add(delta.x, 0.0, 0.0);
                f.tick(position, true);
                velocity = new Vec3(delta.x * 0.8F, 0.0, delta.z * 0.8F);
            }
            // Still crouched from the held shift; the floor stops getFluidFallingAdjustedMovement's -0.005.
            f.input(WADE);
            Vec3 delta = velocity.add(ClientMoveInput.of(f, WADE, 0.3F, 0.02F, yaw));
            return f.tick(position.add(delta.x, 0.0, delta.z), true);
        }
    }

    /**
     * Flowing water over the floor's edge at x = 9 pushes the crouched player toward the drop. Entity#baseTick's
     * current and the keys build deltaMovement along x while Player#maybeBackOffFromEdge holds the position, and
     * releasing shift moves on all of it.
     */
    @Test
    void theCurrentTheEdgeHidesIsReleasedWithShift() throws Exception {
        try (var f = new JavaTickFixture()) {
            flowingTowardTheEdge(f);
            float yaw = 0.0F;
            rotate(f, yaw, 20.0F);
            double z = 0.5;
            f.place(new Vec3(EDGE_X, FLOOR_Y, z), Vec3.ZERO);
            // The source at x = 8 and the level-1 water at x = 9 both flow +x: the player current is 0.014 along x.
            f.input(SHIFT);
            assertTrue(exact(f.tick(new Vec3(EDGE_X, FLOOR_Y, z), true)));
            double velocityX = 0.014D * 0.8F;

            f.input(SNEAK_IN_WATER);
            Vec3 input = ClientMoveInput.of(f, SNEAK_IN_WATER, 0.3F, 0.02F, yaw);
            double velocityZ = 0.0;
            for (int tick = 0; tick < 6; tick++) {
                velocityX += 0.014D + input.x;
                velocityZ += input.z;
                z += velocityZ;
                PredictionResult result = f.tick(new Vec3(EDGE_X, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                velocityX *= 0.8F;
                velocityZ *= 0.8F;
            }

            // Still crouched from the held shift.
            Vec3 release = ClientMoveInput.of(f, WADE, 0.3F, 0.02F, yaw);
            f.input(WADE);
            PredictionResult result = f.tick(
                    new Vec3(EDGE_X + velocityX + 0.014D + release.x, FLOOR_Y, z + velocityZ + release.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /** Still water three blocks deep over the stone floor, which ends at z = 1 above a water-filled drop. */
    private static void pool(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 2; y++) water(f, x, y, z);
                if (z <= 0) {
                    water(f, x, FLOOR_Y - 1, z);
                    water(f, x, FLOOR_Y - 2, z);
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    /** Water sources over the floor, which ends at x = 9, and level-1 water over the drop beyond it. */
    private static void flowingTowardTheEdge(JavaTickFixture f) {
        int flowing = DataTables.defaults().registry().with(BlockIds.WATER.defaultState(), "level", "1");
        for (int z = -8; z <= 8; z++) {
            for (int x = -8; x <= 8; x++) water(f, x, FLOOR_Y, z);
            f.player.compensatedWorld.updateBlock(new BlockPos(9, FLOOR_Y, z), flowing);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void water(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
    }

    private static void assertExact(
            JavaTickFixture f, float yaw, float pitch, double x, double y, double z, boolean onGround) {
        rotate(f, yaw, pitch);
        PredictionResult result = f.tick(recorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    private static void rotate(JavaTickFixture f, float yaw, float pitch) {
        f.player.xRot = yaw;
        f.player.yRot = pitch;
    }

    /** A recorded position moved onto the fixture: its floor top 61 becomes FLOOR_Y and its edge z = 191 becomes 1. */
    private static Vec3 recorded(double x, double y, double z) {
        return new Vec3(x - 218.0, y - 61.0 + FLOOR_Y, z - 190.0);
    }
}
