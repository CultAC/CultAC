package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Friction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaLavaCarryVersionsTest {
    /**
     * LivingEntity#travelInLava applies one drag, chosen by isInShallowFluid(LAVA), and subtracts gravity/4 after
     * getFluidFallingAdjustedMovement.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aSlowFallingBounceInLavaCarriesItsDepthInVanillaOrder(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, false, false));
            var context = f.tick(position, true).getSimulationContext();
            context.setIsSprinting(DesyncStatus.FALSE);
            // Shallow: .0015*.8F enters getFluidFallingAdjustedMovement's -.003 branch, then -.01/4.
            assertEquals(List.of(new Vec3(0.1, -0.003 - 0.01 / 4, 0.2)), drag(f, position, context, true));
            // Deep: .0015*.5 bypasses fluid falling, then -.01/4.
            assertEquals(List.of(new Vec3(0.1, 0.0015 * 0.5 - 0.01 / 4, 0.2)), drag(f, position, context, false));
        }
    }

    private static List<Vec3> drag(JavaTickFixture f, Vec3 position, SimulationContext context, boolean shallow) {
        var velocities = new ArrayList<Vec3>();
        Friction.applyTravelDrag(
                f.player,
                false,
                position,
                new Vec3(0.2, 0.0015, 0.4),
                context,
                true,
                0.01,
                false,
                true,
                shallow,
                velocities);
        return velocities;
    }
}
