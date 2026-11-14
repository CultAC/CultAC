package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#aiStep stops a sprint it started in the same tick once the last move collided hard with a wall, and
 * sendIsSprintingIfNeeded sends only a changed flag, so a START never precedes a sprinting move after a hard collision.
 */
class SprintStartAfterWallTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput WALK =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final float SPRINT_SPEED = (float) ((double) 0.1F * (1.0D + (double) 0.3F));
    private static final float WALK_SPEED = 0.1F;
    private static final double FRICTION = 0.6F * 0.91F;
    private static final float YAW = 22.5F;
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();

    @Test
    void aStartAfterAHardWallCollisionIsFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = alongAWall(f);
            SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
            Vec3 sprint = ClientMoveInput.of(f, SPRINT, 1.0F, SPRINT_SPEED, YAW);
            Vec3 walk = ClientMoveInput.of(f, WALK, 1.0F, WALK_SPEED, YAW);

            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            double slide = sprint.x;
            position = position.add(slide, 0.0, 0.0);
            PredictionResult first = f.tick(position, true);
            assertTrue(exact(first), describe(first));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(first), describe(first));
            assertEquals(0.0, sprintE.violations);

            f.input(WALK);
            f.command(PlayerCommandAction.STOP_SPRINTING);
            slide = slide * FRICTION + walk.x;
            position = position.add(slide, 0.0, 0.0);
            PredictionResult walking = f.tick(position, true);
            assertTrue(exact(walking), describe(walking));
            assertEquals(0.0, sprintE.violations);

            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            slide = slide * FRICTION + sprint.x;
            position = position.add(slide, 0.0, 0.0);
            f.tick(position, true);
            assertEquals(1.0, sprintE.violations);
        }
    }

    @Test
    void aStartIntoAWallFromAFreeMoveIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            Vec3 position = alongAWall(f);
            SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
            Vec3 sprint = ClientMoveInput.of(f, SPRINT, 1.0F, SPRINT_SPEED, YAW);
            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            position = position.add(sprint.x, 0.0, 0.0);
            PredictionResult first = f.tick(position, true);
            assertTrue(exact(first), describe(first));
            assertEquals(0.0, sprintE.violations);
        }
    }

    /**
     * An up piston under the player fires while it walks along the wall. From 26.3 the move carries the
     * PistonMovingBlockEntity#moveCollidedEntities lift after the travel, and that vertical Entity#move clears the
     * travel's horizontal collision, so the next aiStep may start the sprint and keep it.
     */
    @Test
    void aStartAfterAPistonLiftOffTheWallCollisionIsNotFlagged() throws Exception {
        try (var f = new JavaTickFixture()) {
            BlockPos base = new BlockPos(0, FLOOR_Y - 1, 0);
            int upPiston = REGISTRY.with(REGISTRY.block("minecraft:piston").defaultState(), "facing", "up");
            f.player.compensatedWorld.updateBlock(base, upPiston);
            Vec3 position = alongAWall(f);
            SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
            Vec3 sprint = ClientMoveInput.of(f, SPRINT, 1.0F, SPRINT_SPEED, YAW);
            Vec3 walk = ClientMoveInput.of(f, WALK, 1.0F, WALK_SPEED, YAW);

            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            double slide = sprint.x;
            position = position.add(slide, 0.0, 0.0);
            PredictionResult first = f.tick(position, true);
            assertTrue(exact(first), describe(first));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(first), describe(first));

            JavaPistonSlimeLaunchTest.blockEvent(f, base, 0, Direction.UP.get3DDataValue(), upPiston);
            f.input(WALK);
            f.command(PlayerCommandAction.STOP_SPRINTING);
            slide = slide * FRICTION + walk.x;
            position = new Vec3(position.x + slide, FLOOR_Y + 0.51, position.z);
            PredictionResult lifted = f.tick(position, false);
            assertFalse(lifted.getSimulationContext().getWorldData().getPistonPushes().getPistonPush().isEmpty(),
                    describe(lifted));
            assertEquals(0.0, sprintE.violations);

            f.input(SPRINT);
            f.command(PlayerCommandAction.START_SPRINTING);
            slide = slide * 0.91F + sprint.x * 0.02F / SPRINT_SPEED;
            position = new Vec3(position.x + slide, FLOOR_Y + 1.01, position.z);
            PredictionResult started = f.tick(position, false);
            assertEquals(0.0, sprintE.violations, describe(started));
        }
    }

    private static Vec3 alongAWall(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y, 1), BlockIds.STONE.defaultState());
            f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, 1), BlockIds.STONE.defaultState());
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.setExperimentalChecks(true);
        f.player.checkManager.getCheck(SprintE.class).violations = 0;
        f.player.xRot = YAW;
        f.player.yRot = 0.0F;
        Vec3 position = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);
        f.place(position, Vec3.ZERO);
        return position;
    }
}
