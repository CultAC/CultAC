package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaWaterStepVelocityCarryTest {
    /** Real 26.2 client ticks 72-73 of water-enter-exit: stepping changes position Y, not forward deltaMovement. */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_26_2", "V_26_3"})
    void steppingOutOfWaterKeepsForwardVelocity(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            var registry = DataTables.defaults().registry();
            int water = registry.block("minecraft:water").defaultState();
            int flowing = registry.with(water, "level", "1");
            int stair = registry.with(registry.block("minecraft:oak_stairs").defaultState(), "facing", "south");
            stair = registry.with(stair, "waterlogged", "true");
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 2; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 62, z), BlockIds.STONE.defaultState());
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), z == 2 ? stair : water);
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 64, z), z == 2 ? flowing : water);
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            f.place(new Vec3(0.5, 63.5, 2.163017392658693), new Vec3(0, -0.005, 0.09937754411139528));
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            var stepped = f.tick(new Vec3(0.5, 64.0, 2.288994936713464), true);
            assertTrue(exact(stepped), describe(stepped));
            var trace = stepped.getValidMovements().getClosestTrace();
            var carry = f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels();
            assertTrue(
                    carry.stream().anyMatch(v -> Math.abs(v.z - 0.10078203674558603) < 1e-8),
                    "carry=" + carry + " trace=" + trace + " prediction="
                            + stepped.getLegacyLikePredictionVector()
                            + " closest=" + stepped.getAcceptedClosestToTarget() + " collisions="
                            + stepped.getCollideAxisData());
            var next = f.tick(new Vec3(0.5, 64.0, 2.4233769734024255), true);
            assertTrue(exact(next), describe(next));
        }
    }
}
