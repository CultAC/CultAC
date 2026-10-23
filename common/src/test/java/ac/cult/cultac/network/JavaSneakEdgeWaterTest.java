package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Set;
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
    private static final ServerboundPlayerInput SNEAK_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final ServerboundPlayerInput WALK =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput WADE =
            new ServerboundPlayerInput(true, false, true, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final ServerboundPlayerInput SWIM =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SWIM_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
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

    /**
     * A sprint-swimmer slides past the floor's edge into a one-block trench while Entity#collide still holds it on
     * the floor, then holds shift. Player#isAboveGround keeps the backoff on from that onGround, every direction can
     * fall, so the move keeps only goDownInWater and Player#travel's swim pull, while deltaMovement keeps the swim on
     * X and Z. The moves are a real client's, translated onto the fixture: the trench spans x = 1 to 3.
     */
    @Test
    void theSwimTheEdgeHidesCarriesIntoTheFall() throws Exception {
        try (var f = new JavaTickFixture()) {
            trench(f);
            Vec3 previous = trenchRecorded(-45.29452866977943, 56.0, 482.9954575760281);
            Vec3 start = trenchRecorded(-45.14097303168874, 56.0, 483.0076885480256);
            // LivingEntity#travelInWater keeps 0.9F of a sprinting swimmer's horizontal delta.
            f.place(start, new Vec3((start.x - previous.x) * 0.9F, 0.0, (start.z - previous.z) * 0.9F));
            rotate(f, -88.24232F, 38.497887F);
            f.input(SWIM);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            assertTrenchExact(f, -88.30942F, 38.497887F, -44.98318150571286, 56.0, 483.0192732310736, true);
            assertTrenchExact(f, -88.30942F, 38.564987F, -44.82157768074129, 56.0, 483.0302762540824, true);
            // Entity#collide resolves Y before X, so the box ends past the edge still on the ground.
            assertTrenchExact(f, -88.30942F, 38.564987F, -44.65654278676451, 56.0, 483.04075578306987, true);

            f.input(SWIM_SHIFT);
            assertTrenchExact(
                    f, -88.30942F, 38.564987F, -44.65654278676451, 55.91041429121047, 483.04075578306987, false);
            assertTrenchExact(
                    f, -88.37652F, 38.632088F, -44.48564011196552, 55.7551754109778, 483.0503175971898, false);
            assertTrenchExact(
                    f, -88.37652F, 38.632088F, -44.312235600313436, 55.55187840853262, 483.05947749808564, false);

            f.input(SWIM);
            assertTrenchExact(
                    f, -88.44362F, 38.69919F, -44.13657880937065, 55.3499264985831, 483.06825313623335, false);
            assertTrenchExact(
                    f, -88.44362F, 38.69919F, -43.958894967119925, 55.14895919635507, 483.07668293791687, false);
            assertTrenchExact(f, -88.44362F, 38.69919F, -43.779386778740395, 55.0, 483.08480148679087, true);
        }
    }

    /**
     * A crouched player walking on the pool floor straight at its edge holds its position while deltaMovement builds
     * toward the drop. LivingEntity#travelInWater's drag follows the sprint flag, so each hidden tick leaves a single
     * deltaMovement, and releasing shift moves on it.
     */
    @Test
    void aHeldEdgeCarriesOneDeltaMovement() throws Exception {
        try (var f = new JavaTickFixture()) {
            pool(f);
            float yaw = 180.0F;
            rotate(f, yaw, 20.0F);
            Vec3 held = new Vec3(0.5, FLOOR_Y, 0.701);
            f.place(held, Vec3.ZERO);
            f.input(SHIFT);
            assertTrue(exact(f.tick(held, true)));

            f.input(SNEAK_FORWARD);
            Vec3 input = ClientMoveInput.of(f, SNEAK_FORWARD, 0.3F, 0.02F, yaw);
            double velocityZ = 0.0;
            for (int tick = 0; tick < 20; tick++) {
                PredictionResult result = f.tick(held, true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                Set<Vec3> carried =
                        f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
                assertEquals(1, carried.size(), "tick " + tick + " " + carried);
                velocityZ = (velocityZ + input.z) * 0.8F;
            }

            // Still crouched from the held shift; Entity#collide resolves Y on the floor before Z leaves it.
            f.input(WALK);
            Vec3 release = ClientMoveInput.of(f, WALK, 0.3F, 0.02F, yaw);
            PredictionResult result = f.tick(held.add(0.0, 0.0, velocityZ + release.z), true);
            assertTrue(exact(result), describe(result));
        }
    }

    /**
     * A sprint-swimmer on the floor holds shift into its x = 9 edge and lets go. LocalPlayer#aiStep never crouches a
     * swimming player, so the input the edge hides keeps the full scale and the release moves on all of it.
     */
    @Test
    void aSwimmerHeldAtAnEdgeMovesOnWithTheFullInput() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 12; x++) {
                for (int z = -8; z <= 8; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) water(f, x, y, z);
                    if (x >= 9) {
                        water(f, x, FLOOR_Y - 1, z);
                        water(f, x, FLOOR_Y - 2, z);
                    }
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            float yaw = -90.0F;
            rotate(f, yaw, 20.0F);
            double x = 8.8;
            f.place(new Vec3(x, FLOOR_Y, 0.5), Vec3.ZERO);
            f.input(SWIM);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            Vec3 input = ClientMoveInput.of(f, SWIM, 1.0F, 0.02F, yaw);
            double velocityX = 0.0;
            double velocityZ = 0.0;
            double z = 0.5;
            int hidden = 0;
            for (int tick = 0; tick < 15; tick++) {
                boolean shift = tick >= 3 && tick < 14;
                f.input(shift ? SWIM_SHIFT : SWIM);
                double deltaX = velocityX + input.x;
                double movedX = deltaX;
                // Player#maybeBackOffFromEdge steps the move back by 0.05 while the deflated box could fall.
                while (shift && movedX != 0.0 && x - 0.3 + 1.0E-7 + movedX >= 9.0) {
                    movedX = Math.abs(movedX) <= 0.05 ? 0.0 : movedX - 0.05;
                }
                if (shift && movedX == 0.0) hidden++;
                x += movedX;
                z += velocityZ + input.z;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                // LivingEntity#travelInWater slows a sprinting player by 0.9F.
                velocityX = deltaX * 0.9F;
                velocityZ = (velocityZ + input.z) * 0.9F;
            }
            assertTrue(hidden >= 3, "hidden ticks " + hidden);
        }
    }

    /** Still water four blocks deep over the stone floor, with a one-block water trench at x = 1 and 2. */
    private static void trench(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) water(f, x, y, z);
                if (x == 1 || x == 2) {
                    water(f, x, FLOOR_Y - 1, z);
                    f.player.compensatedWorld.updateBlock(
                            new BlockPos(x, FLOOR_Y - 2, z), BlockIds.STONE.defaultState());
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertTrenchExact(
            JavaTickFixture f, float yaw, float pitch, double x, double y, double z, boolean onGround) {
        rotate(f, yaw, pitch);
        PredictionResult result = f.tick(trenchRecorded(x, y, z), onGround);
        assertTrue(exact(result), describe(result));
    }

    /** A recorded trench position moved onto the fixture: its floor top 56 becomes FLOOR_Y and its edge -45 becomes 1. */
    private static Vec3 trenchRecorded(double x, double y, double z) {
        return new Vec3(x + 46.0, y - 56.0 + FLOOR_Y, z - 483.0);
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
