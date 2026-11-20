package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.groundspoof.NoFallExecutor;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A falling player that lands beside a bottom slab and steps up onto it. Entity#collide steps after the collision that
 * stopped the downward move, so Entity#move sets verticalCollisionBelow and onGround, and Entity#checkFallDamage lands
 * the fall, though the move ends above where it started. The server resets the fall for an upward move it receives off
 * the ground, so an airborne claim for this move must reach it on the ground.
 */
class JavaStepLandingGroundClaimTest {
    /** Falling the last part of a long drop, its box just short of the slab ahead. */
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 0.19, 0.69);

    private static final Vec3 FALL = new Vec3(0, -1.2, 0.1);
    private static final Vec3 ON_SLAB = new Vec3(START.x, FLOOR_Y + 0.5, START.z + FALL.z);

    /** The control: the step onto the slab claims the ground it landed on. */
    @Test
    void aStepFromAFallClaimsTheGround() throws Exception {
        try (var f = fallingBesideTheSlab()) {
            PredictionResult step = f.tick(ON_SLAB, true);
            assertTrue(exact(step), describe(step));
        }
    }

    @Test
    void anAirborneClaimForAStepFromAFallReachesTheServerOnTheGround() throws Exception {
        try (var f = fallingBesideTheSlab()) {
            PacketReceiveEvent<ServerboundMovePlayer> move = f.move(ON_SLAB, false);
            f.tickEnd();
            PredictionResult result =
                    f.player.checkManager.getSimulationProcessor().getLastPrediction();

            assertRejected(result, "GroundSpoof");
            assertTrue(move.getPacket().onGround(), "the server received the airborne claim");
        }
    }

    private static JavaTickFixture fallingBesideTheSlab() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.checkManager.getListener(NoFallExecutor.class).setEnabled(true);
        var registry = DataTables.defaults().registry();
        f.player.compensatedWorld.updateBlock(
                new BlockPos(0, FLOOR_Y, 1),
                registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "bottom"));
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(START, FALL);
        return f;
    }
}
