package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaSneakEdgeCarryVersionsTest {
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {
                "V_1_21_2",
                "V_1_21_4",
                "V_1_21_5",
                "V_1_21_6",
                "V_1_21_7",
                "V_1_21_9",
                "V_1_21_11",
                "V_26_1",
                "V_26_2",
                "V_26_3"
            })
    void releasingAnEdgeUsesTheVelocityTheBackoffHid(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            for (int x = -8; x <= 8; x++) {
                for (int z = 1; z <= 8; z++) {
                    f.player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), BlockIds.AIR.defaultState());
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            Vec3 position = new Vec3(0.5, 64, 1.299999);
            double velocity = 0.05;
            f.place(position, new Vec3(0, -0.0784000015258789, velocity));
            f.input(new ServerboundPlayerInput(true, false, false, false, false, true, false));
            for (int tick = 0; tick < 4; tick++) {
                // With one forward key the pre/post-1.21.5 input pipelines have the same result.
                float input = tick == 0 ? 0.98F : 0.98F * 0.3F;
                velocity = (velocity + (double) input * 0.1F) * (0.6F * 0.91F);
                var held = f.tick(position, true);
                assertTrue(exact(held), describe(held));
                double expected = velocity;
                assertTrue(
                        f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels().stream()
                                .anyMatch(v -> Math.abs(v.z - expected) < 1e-8),
                        "held tick " + tick + " carry="
                                + f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels());
            }
            // LocalPlayer computes crouching before reading this tick's released key, so this move is
            // still slow.
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            position = position.add(0, 0, velocity + (double) (0.98F * 0.3F) * 0.1F);
            var released = f.tick(position, true);
            assertTrue(exact(released), describe(released));
        }
    }
}
