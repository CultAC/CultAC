package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;

/** Fluid pools and inputs for the fluid movement tests. */
final class FluidPools {
    static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);

    private FluidPools() {}

    /** A fluid column filling x and z in -8..8 from the floor to {@code topY} inclusive. */
    static JavaTickFixture pool(BlockDefinition fluid, int topY) throws Exception {
        var f = new JavaTickFixture();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR_Y; y <= topY; y++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), fluid.defaultState());
                }
            }
        }
        settle(f);
        return f;
    }

    static void settle(JavaTickFixture f) {
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    static void stoneWall(JavaTickFixture f, int x, int topY) {
        for (int z = -8; z <= 8; z++) {
            for (int y = FLOOR_Y; y <= topY; y++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.STONE.defaultState());
            }
        }
        settle(f);
    }

    /** One client tick whose move packet reports {@code horizontalCollision}. */
    static PredictionResult tick(JavaTickFixture f, Vec3 to, boolean onGround, boolean horizontalCollision) {
        PredictionResult before = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        var packet = new ServerboundMovePlayer(
                to.x, to.y, to.z, f.player.xRot, f.player.yRot, onGround, horizontalCollision, true, true);
        f.listener.onMovePlayer(f.event(ServerboundPackets.MOVE_PLAYER, packet), f.player, packet);
        f.tickEnd();
        PredictionResult result = f.player.checkManager.getSimulationProcessor().getLastPrediction();
        assertNotNull(result);
        if (result == before) throw new AssertionError("the tick committed no prediction");
        return result;
    }

    static String report(PredictionResult result) {
        StringBuilder text = new StringBuilder(JavaTickFixture.describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(" [").append(flag.getVerbose().getString()).append(" sev ").append(flag.getSeverity()).append(']');
        }
        return text.toString();
    }
}
