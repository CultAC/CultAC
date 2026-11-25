package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;

/** Fluid pools and inputs for the fluid movement tests. */
final class FluidPools {
    static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);

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
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
    }

    static String report(PredictionResult result) {
        StringBuilder text = new StringBuilder(JavaTickFixture.describe(result));
        for (PredictionResult.Flag flag : result.getFlags()) {
            text.append(" [").append(flag.getVerbose().getString()).append(" sev ").append(flag.getSeverity()).append(']');
        }
        return text.toString();
    }
}
