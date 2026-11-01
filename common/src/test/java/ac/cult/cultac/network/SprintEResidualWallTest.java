package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.ClientHorizontalCollision;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.sprint.SprintE;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * LocalPlayer#shouldStopRunSprinting stops the sprint after a hard horizontal collision. A client that keeps sprinting
 * along the wall and adds a residual below Simulation's flag threshold to every move must still meet SprintE: the
 * accepted move commits its explaining candidate, collision included.
 */
class SprintEResidualWallTest {
    private static final ServerboundPlayerInput SPRINT =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    /** Along the wall, below Simulation's 0.001 threshold but above the 1e-5 exact-input tolerance. */
    private static final double RESIDUAL = 3.0E-4;

    @Test
    void aSubThresholdResidualDoesNotHideAHardWallCollision() throws Exception {
        assertEquals(1.0, violationsAfterTwoBlockedSprints(RESIDUAL));
    }

    @Test
    void anExactWallSprintIsStillFlagged() throws Exception {
        assertEquals(1.0, violationsAfterTwoBlockedSprints(0.0));
    }

    private static double violationsAfterTwoBlockedSprints(double residual) throws Exception {
        // The engine's own exact ends of the two blocked sprints; each probe starts a fresh fixture.
        Vec3 first, second;
        try (var probe = new JavaTickFixture()) {
            setUp(probe);
            first = end(sprintTick(probe, START));
        }
        try (var probe = new JavaTickFixture()) {
            setUp(probe);
            sprintTick(probe, first);
            second = end(sprintTick(probe, first));
        }
        try (var f = new JavaTickFixture()) {
            SprintE sprintE = setUp(f);
            // Along the wall: the X move is unconstrained, so the residual carries into the next start.
            PredictionResult blocked = sprintTick(f, first.add(residual, 0, 0));
            assertEquals(DesyncStatus.TRUE, ClientHorizontalCollision.hard(blocked), describe(blocked));
            assertTrue(blocked.getOffset() < 1.0E-3, describe(blocked));
            assertEquals(0.0, sprintE.violations);
            PredictionResult kept = sprintTick(f, second.add(2 * residual, 0, 0));
            assertTrue(kept.getOffset() < 1.0E-3, describe(kept));
            return sprintE.violations;
        }
    }

    private static final Vec3 START = new Vec3(0.5, FLOOR_Y, 1.0 - 0.3F);

    /** A sprinting player standing against the wall at z = 1, facing yaw 22.5 into it. */
    private static SprintE setUp(JavaTickFixture f) {
        for (int x = -8; x <= 8; x++) {
            stone(f, x, FLOOR_Y, 1);
            stone(f, x, FLOOR_Y + 1, 1);
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.setExperimentalChecks(true);
        SprintE sprintE = f.player.checkManager.getCheck(SprintE.class);
        sprintE.violations = 0;
        // Forward at yaw 22.5 meets the wall with every key direction at least 22.5 degrees off it.
        f.player.xRot = 22.5F;
        f.player.yRot = 0.0F;
        f.place(START, Vec3.ZERO);
        f.player.onGround = true;
        f.player.lastOnGround = true;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
        f.input(SPRINT);
        var packet = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
        PacketReceiveEvent<ServerboundPlayerCommand> event = f.event(ServerboundPackets.PLAYER_COMMAND, packet);
        new PacketEntityAction().onPlayerCommand(event, f.player, packet);
        sprintE.onPlayerCommand(event, f.player, packet);
        return sprintE;
    }

    private static PredictionResult sprintTick(JavaTickFixture f, Vec3 to) {
        return f.tick(to, true);
    }

    private static Vec3 end(PredictionResult result) {
        return result.getSimulationContext().getStart().add(result.getAcceptedClosestToTarget());
    }

    private static void stone(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
    }
}
