package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A grounded riptide release against a wall. TridentItem#releaseUsing runs Entity#move(SELF, (0, 1.1999999F, 0))
 * before LocalPlayer#aiStep; that move has no horizontal part, so it clears horizontalCollision and the sprint the
 * same aiStep starts is not stopped by the wall the last travel collided with.
 */
class SprintERiptideLiftTest {
    private static final ServerboundPlayerInput SPRINT_FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);

    @Test
    void aSprintStartedOnALiftingReleaseIsNotStoppedByTheWall() throws Exception {
        Vec3 blocked, released;
        try (var probe = new JavaTickFixture()) {
            setUp(probe);
            blocked = end(probe.tick(START, true));
        }
        try (var probe = new JavaTickFixture()) {
            setUp(probe);
            probe.tick(blocked, true);
            release(probe);
            released = end(probe.tick(blocked.add(0, 1.0, 0), false));
        }
        try (var f = new JavaTickFixture()) {
            SprintE sprintE = setUp(f);
            PredictionResult wall = f.tick(blocked, true);
            assertTrue(exact(wall), describe(wall));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hardForEveryReality(wall), describe(wall));
            release(f);
            PredictionResult lifted = f.tick(released, false);
            assertTrue(exact(lifted), describe(lifted));
            assertTrue(released.y > blocked.y + 1.0, "the release lifted the player");
            assertEquals(0.0, sprintE.violations);
        }
    }

    /** A player standing against the wall at z = 1, facing it at yaw 0 with forward and sprint held, not sprinting. */
    private static SprintE setUp(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, 1), BlockIds.STONE.defaultState());
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.player.setExperimentalChecks(true);
        SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
        sprintE.violations = 0;
        f.player.xRot = 0.0F;
        f.player.yRot = 0.0F;
        f.place(START, Vec3.ZERO);
        f.input(SPRINT_FORWARD);
        return sprintE;
    }

    /** A certain riptide I release, then the START_SPRINTING LocalPlayer#sendIsSprintingIfNeeded sends before the move. */
    private static void release(JavaTickFixture f) {
        f.player.packetStateData.riptideLevel = 1;
        f.player.packetStateData.riptideReleaseCertain = true;
        f.player.riptideSpinAttackTicks = 20;
        f.command(PlayerCommandAction.START_SPRINTING);
    }

    private static Vec3 end(PredictionResult result) {
        return result.getSimulationContext().getStart().add(result.getAcceptedClosestToTarget());
    }
}
