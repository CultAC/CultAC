package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.checks.psuedo.InputKeys;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.data.CollideAxisData.CollideResult;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 26.3 client sprint-jumps forward so it lands touching a floor and a wall in the same tick, once into the corner of
 * a raised floor and once onto the edge of a step. Its collided movement stays within 8 degrees of its input, so
 * LocalPlayer#shouldStopRunSprinting keeps the sprint until a later move along the wall is hard. The moves are a
 * real client's at their recorded coordinates. LivingEntity#moveRelative adds each tick's input to deltaMovement before
 * Entity#move, so the momentum the landing starts from carries the previous tick's input.
 */
class JavaSprintCornerCollisionTest {
    private static final ServerboundPlayerInput JUMP_SPRINT =
            new ServerboundPlayerInput(true, false, false, false, true, false, true);
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput FORWARD_RIGHT_SPRINT =
            new ServerboundPlayerInput(true, false, false, true, false, false, true);
    private static final float CANONICAL = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    // LivingEntity#travelInAir's ground friction.
    private static final double GROUND_FRICTION = 0.6F * 0.91F;

    @Test
    void landingInACornerKeepsTheSprint() throws Exception {
        try (var f = new JavaTickFixture()) {
            Client c = landInACorner(f);
            // The jump's movement along the wall is hard, so the client stops sprinting.
            PredictionResult jump = c.move(121.45130624068176, 73.41999998688698, 165.69999998807907, false);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(jump), describe(jump));
            c.command(PlayerCommandAction.STOP_SPRINTING);
            PredictionResult rise =
                    c.move(-353.29773F, 29.72565F, 121.4102639754279, 73.7531999805212, 165.69999998807907, false);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(rise), describe(rise));
            c.move(-352.76086F, 30.195354F, 121.37044541000586, 74.00133597911214, 165.71944371692285, false);
        }
    }

    /**
     * The real client's recorded moves from {@link #landingInACornerKeepsTheSprint}, replayed without the
     * STOP_SPRINTING it sent after the jump. That test asserts the jump's collision is hard, on which
     * LocalPlayer#shouldStopRunSprinting stops the sprint; SprintE adds one violation for each later move that still
     * sprints. Its air moves travelled by the walking flying speed, short of the sprint flag's Player#getFlyingSpeed:
     * the wall clips the first to the same end either way, but the reported keys do not explain the second.
     */
    @Test
    void sprintContinuingAfterAHardCollisionAddsViolations() throws Exception {
        try (var f = new JavaTickFixture()) {
            Client c = landInACorner(f);
            c.move(121.45130624068176, 73.41999998688698, 165.69999998807907, false);
            f.player.xRot = -353.29773F;
            f.player.yRot = 29.72565F;
            c.move(1.0, 121.4102639754279, 73.7531999805212, 165.69999998807907, false);
            f.player.xRot = -352.76086F;
            f.player.yRot = 30.195354F;
            c.moveAgainstTheKeys(2.0, 121.37044541000586, 74.00133597911214, 165.71944371692285);
        }
    }

    @Test
    void landingOnAnEdgeKeepsTheSprint() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int chunkX = 4; chunkX <= 5; chunkX++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, 9);
            }
            for (int x = 70; x <= 90; x++) {
                for (int z = 146; z <= 157; z++) {
                    stone(f, x, 80, z);
                    if (x <= 79) stone(f, x, 81, z);
                }
            }
            Client c = client(f);
            c.groundStart(82.9719768461373, 151.92664590213354, 82.77339144682172, 81.0, 151.90665666185885);

            f.input(JUMP_SPRINT);
            c.command(PlayerCommandAction.START_SPRINTING);
            c.move(-265.0621F, 21.740747F, 82.33877914116344, 81.41999998688698, 151.86755867638976, false);
            c.move(-265.53186F, 23.686646F, 82.0760783477548, 81.7531999805212, 151.84422470015537, false);
            c.move(-266.20294F, 25.699642F, 81.81159667585487, 82.00133597911214, 151.82130155885778, false);
            f.input(SPRINT);
            c.move(-266.47137F, 27.242945F, 81.54548674858188, 82.16610926093821, 151.79887173259084, false);
            c.move(-266.47137F, 28.18235F, 81.2778951088767, 82.24918707874468, 151.7768908230147, false);
            c.move(-266.6056F, 29.054657F, 81.00895142183319, 82.25220334025373, 151.75537938769904, false);
            c.move(-266.6727F, 29.926964F, 80.73877565247815, 82.17675927506424, 151.73432443793016, false);
            c.move(-266.7398F, 30.530869F, 80.46747700391987, 82.02442408821369, 151.7137141558766, false);
            // The step at x = 80 clips the fall; (-0.1675, -0.0202) lies 4 degrees from the forward input.
            PredictionResult edge =
                    c.move(-266.7398F, 30.73217F, 80.30000001192093, 81.79673560066871, 151.6935085214557, false);
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(edge), describe(edge));
            // The fall along the step is hard, so the client stops sprinting.
            f.input(JUMP_SPRINT);
            PredictionResult fall =
                    c.move(-266.7398F, 30.79927F, 80.30000001192093, 81.49520087700593, 151.6736711163911, false);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(fall), describe(fall));
            c.command(PlayerCommandAction.STOP_SPRINTING);
            PredictionResult lower =
                    c.move(-266.6056F, 30.933472F, 80.30000001192093, 81.1212968405392, 151.6544584564404, false);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(lower), describe(lower));
            PredictionResult landing =
                    c.move(-265.59903F, 31.403175F, 80.30000001192093, 81.0, 151.63546936047692, true);
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(landing), describe(landing));
            c.move(-262.71387F, 32.14128F, 80.30000001192093, 81.41999998688698, 151.60575488548105, false);
        }
    }

    @Test
    void sprintingOnAfterAHardWallCollisionIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int x = -8; x <= 8; x++) {
                stone(f, x, FLOOR_Y, 1);
                stone(f, x, FLOOR_Y + 1, 1);
            }
            Client c = client(f);
            // Forward at yaw 22.5 meets the wall at z = 1 with every key direction at least 22.5 degrees off the wall.
            float yaw = 22.5F;
            f.player.xRot = yaw;
            f.player.yRot = 0.0F;
            Vec3 position = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);
            f.place(position, Vec3.ZERO);
            Vec3 input = ClientMoveInput.of(f, SPRINT, 1.0F, CANONICAL, yaw);

            f.input(SPRINT);
            c.command(PlayerCommandAction.START_SPRINTING);
            position = position.add(input.x, 0.0, 0.0);
            PredictionResult blocked = f.tick(position, true);
            assertTrue(exact(blocked), describe(blocked));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(blocked), describe(blocked));
            assertEquals(0.0, c.sprintE.violations);

            position = position.add(input.x * GROUND_FRICTION + input.x, 0.0, 0.0);
            PredictionResult kept = f.tick(position, true);
            assertTrue(exact(kept), describe(kept));
            assertEquals(1.0, c.sprintE.violations);
        }
    }

    /**
     * A 26.3 client sprint-jumps into the side of a block at z = 147 at its recorded coordinates. The collided
     * movement (-0.1167, -0.2917) lies 0.1396238 radians from the forward input at yaw 886.1995, which Mth's sine
     * table puts under LocalPlayer#isHorizontalCollisionMinor's 0.13962634, so the client keeps sprinting until the
     * rise along the block is hard.
     */
    @Test
    void jumpingIntoABlockJustUnder8DegreesKeepsTheSprint() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int chunkX = 0; chunkX <= 1; chunkX++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, 9);
            }
            for (int x = 10; x <= 22; x++) {
                for (int z = 140; z <= 155; z++) stone(f, x, 61, z);
            }
            stone(f, 16, 62, 146);
            Client c = client(f);
            c.groundStart(16.570901359113908, 149.20710546106022, 16.479165851780152, 62.0, 148.93996367186404);

            f.input(SPRINT);
            c.command(PlayerCommandAction.START_SPRINTING);
            c.move(882.7495F, 5.2499833F, 16.391290304827113, 62.0, 148.67243734220042, true);
            c.move(884.0995F, 5.5499835F, 16.308402048717753, 62.0, 148.403843747497, true);
            c.move(884.99945F, 5.6999836F, 16.230167572752563, 62.0, 148.1341337227423, true);
            c.move(885.8995F, 5.8499837F, 16.156412935629756, 62.0, 147.86331084392864, true);
            c.move(886.1995F, 5.999984F, 16.085744401744467, 62.0, 147.59172130568948, true);
            f.input(JUMP_SPRINT);
            PredictionResult jump = c.move(15.969039539170254, 62.41999998688698, 147.30000001192093, false);
            PredictionResult rise = c.move(15.899238978119625, 62.7531999805212, 147.30000001192093, false);
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(jump), describe(jump));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(rise), describe(rise));
            c.command(PlayerCommandAction.STOP_SPRINTING);
            c.move(15.831043774303744, 63.00133597911215, 147.28096613240973, false);
        }
    }

    /**
     * A 26.3 client sprinting forward at yaw -632.1592 meets a wall at z = 9982 with the collided movement (-0.2631,
     * -0.0065), 3.6 degrees from its reported forward key: a minor collision. The engine state is a real client's
     * move, where entity push allows starting velocities from which the forward key reaches the move, though the
     * closest trace's does not.
     */
    @Test
    void aMinorCollisionWithTheReportedKeysKeepsTheSprintFromAnyAcceptedStart() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.input(SPRINT);
            Vec3 target = new Vec3(-0.2630985389369016, 0.0, -0.006483230190497125);
            PredictionResult result = collision(
                    f,
                    -632.1592F,
                    0.13000001F,
                    new Vec3(-0.17051271645420168, -0.0784000015258789, -0.0028061151376537375),
                    new SimpleCollisionBox(
                            -0.17056841797648192,
                            -0.0784000015258789,
                            -0.07071067811865477,
                            -0.029147061739172392,
                            -0.0784000015258789,
                            0.07071067811865477,
                            false),
                    target,
                    new CollideResult(false, target.x),
                    new CollideResult(true, target.z, -1));
            assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(result));
        }
    }

    /**
     * A client sprint-jumps at yaw -45 with W into a wall at x + 0.0084, a hard collision at 45 degrees, and reports
     * W+D, which would point along the wall. From the only accepted start, x = -0.01, W+D never reaches the wall, so
     * the reported keys cannot have made the move and W's hard collision stands.
     */
    @Test
    void reportedKeysThatCannotReachTheMoveDoNotMakeItMinor() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.input(FORWARD_RIGHT_SPRINT);
            Vec3 start = new Vec3(-0.01, -0.0784000015258789, 0.2);
            Vec3 target = new Vec3(0.0, -0.0784000015258789, 0.2 + 0.026F * Math.sqrt(0.5));
            PredictionResult result = collision(
                    f,
                    -45.0F,
                    0.026F,
                    start,
                    new SimpleCollisionBox(start.x, start.y, start.z, start.x, start.y, start.z, false),
                    target,
                    new CollideResult(true, target.x, 1),
                    new CollideResult(false, target.z));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(result));
        }
    }

    /** A move from the closest starting movement {@code closest}, accepted from any start within {@code starts}. */
    private static PredictionResult collision(
            JavaTickFixture f,
            float yaw,
            float inputRadius,
            Vec3 closest,
            SimpleCollisionBox starts,
            Vec3 target,
            CollideResult x,
            CollideResult z) {
        SimulationContext context = mock(SimulationContext.class);
        doReturn(yaw).when(context).getXRot();
        doReturn((double) inputRadius).when(context).getHorizontalInputRadius(any(), any());
        ValidMovements valid = mock(ValidMovements.class);
        doReturn(new MovementTrace(new PredVector(closest), Vec3.ZERO, Vec3.ZERO, closest, closest))
                .when(valid)
                .getClosestTrace();
        doReturn(starts).when(valid).getCollisionIgnoredMaxStartingVelExtents();
        PredictionResult result = new PredictionResult(
                f.player, new PredVector(closest), context, target, null, List.of(), List.of());
        result.setValidMovements(valid);
        result.setCollideAxisData(new CollideAxisData(
                x, new CollideResult(false, 0.0), new CollideResult(true, 0.0), z, List.of()));
        return result;
    }

    /** Sprint-jumps forward into the corner of a raised floor and lands touching the wall at z = 166. */
    private static Client landInACorner(JavaTickFixture f) {
        for (int chunkX = 6; chunkX <= 8; chunkX++) {
            for (int chunkZ = 9; chunkZ <= 11; chunkZ++) {
                f.player.compensatedWorld.ensureValidationChunkLoaded(chunkX, chunkZ);
            }
        }
        for (int x = 112; x <= 127; x++) {
            for (int z = 156; z <= 170; z++) {
                stone(f, x, 72, z);
                if (z >= 166) stone(f, x, 73, z);
            }
        }
        Client c = client(f);
        c.groundStart(122.07070437744618, 162.0309428836821, 122.022564469627, 73.0, 162.28826776903628);

        f.input(JUMP_SPRINT);
        c.command(PlayerCommandAction.START_SPRINTING);
        c.move(-352.02277F, 22.948555F, 121.95081910581396, 73.41999998688698, 162.7529956005854, false);
        c.move(-352.55957F, 24.69316F, 121.90834535376196, 73.7531999805212, 163.03200232294301, false);
        c.move(-353.49905F, 27.108751F, 121.86680809448174, 74.00133597911214, 163.31121446081085, false);
        f.input(SPRINT);
        c.move(-353.70038F, 29.054646F, 121.82621286586786, 74.16610926093821, 163.5906236050661, false);
        c.move(-353.36484F, 29.65855F, 121.78632681924243, 74.24918707874468, 163.87019523865726, false);
        c.move(121.7470861282451, 74.25220334025373, 164.14991473754836, false);
        c.move(121.70843271088626, 74.17675927506424, 164.4297687938663, false);
        c.move(121.67031371255383, 74.02442408821369, 164.70974529744615, false);
        c.move(121.63268103554948, 73.79673560066871, 164.98983322803758, false);
        c.move(121.59549091096642, 73.49520087700593, 165.27002255721249, false);
        c.move(121.55870350909834, 73.1212968405392, 165.550304159101, false);
        // The wall at z = 166 clips the landing; (-0.0364, 0.1497) lies 7 degrees from the forward input.
        f.input(JUMP_SPRINT);
        PredictionResult landing = c.move(121.52228258491145, 73.0, 165.69999998807907, true);
        assertEquals(DesyncStatus.FALSE, ClientHorizontalCollision.hard(landing), describe(landing));
        return c;
    }

    private static void stone(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
    }

    /** Settles the placed blocks and enables SprintE. */
    private static Client client(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.setExperimentalChecks(true);
        SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
        sprintE.violations = 0;
        return new Client(f, sprintE);
    }

    private record Client(JavaTickFixture f, SprintE sprintE) {
        /** Stands on the floor at y with the deltaMovement its ground move from (fromX, fromZ) left. */
        void groundStart(double fromX, double fromZ, double x, double y, double z) {
            f.place(new Vec3(x, y, z), new Vec3((x - fromX) * GROUND_FRICTION, 0.0, (z - fromZ) * GROUND_FRICTION));
            f.player.onGround = true;
            f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        }

        PredictionResult move(float yaw, float pitch, double x, double y, double z, boolean onGround) {
            f.player.xRot = yaw;
            f.player.yRot = pitch;
            return move(x, y, z, onGround);
        }

        /** A move at the current rotation; the prediction is exact and SprintE stays quiet. */
        PredictionResult move(double x, double y, double z, boolean onGround) {
            return move(0.0, x, y, z, onGround);
        }

        /** A move at the current rotation; the prediction is exact and SprintE holds {@code violations} after it. */
        PredictionResult move(double violations, double x, double y, double z, boolean onGround) {
            PredictionResult result = f.tick(new Vec3(x, y, z), onGround);
            assertTrue(exact(result), describe(result));
            assertEquals(violations, sprintE.violations, describe(result));
            return result;
        }

        /** An air move the engine predicts exactly but the reported keys do not; SprintE holds {@code violations}. */
        PredictionResult moveAgainstTheKeys(double violations, double x, double y, double z) {
            PredictionResult result = f.tick(new Vec3(x, y, z), false);
            assertTrue(result.getOffset() < 1.0E-5, describe(result));
            assertTrue(result.hasFlag(InputKeys.class), describe(result));
            assertEquals(violations, sprintE.violations, describe(result));
            return result;
        }

        void command(PlayerCommandAction action) {
            var packet = new ServerboundPlayerCommand(f.player.entityID, action, 0);
            PacketReceiveEvent<ServerboundPlayerCommand> event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
            new PacketEntityAction().onPlayerCommand(event, f.player, packet);
            sprintE.onPlayerCommand(event, f.player, packet);
        }
    }
}
