package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaBlockEntityPass;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPassState;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.network.packet.RegistryTags;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.ClientPistonDeltas;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.latency.ClientComponentRegistries;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Entity#move's client-side checkFallDamage gate, including 1.21.4's !isControlledByClient. */
class JavaFallCallbackVersionsTest {
    private static final Vec3 START = new Vec3(0.5, FLOOR_Y + 8, 0.5);
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    @Test
    void aReceivedWaterTagReloadAlsoChangesTheFallCallbacksBodyContact() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 8, 0), BlockIds.WATER.defaultState());
            var box = new SimpleCollisionBox(0.2, FLOOR_Y + 8.2, 0.2, 0.8, FLOOR_Y + 10, 0.8);
            assertTrue(JavaFallDistance.touchesWater(f.player, box));
            if (f.player.registryState == null) f.player.registryState = new ClientComponentRegistries();
            f.player.registryState.appendTags(new RegistryTags(
                    Map.of("minecraft:fluid", new RegistryTags.Payload(Map.of("minecraft:water", List.of())))));
            assertFalse(
                    JavaFallDistance.touchesWater(f.player, box),
                    "the fall callback must use the received water tag for the body query too");
        }
    }

    @ParameterizedTest(name = "{0}: ordinary travel invokes the release's fall callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void ordinaryTravelUsesTheClientFallCallbackGate(String release, ClientVersion version) throws Exception {
        try (var f = falling(version)) {
            var result = f.tick(START.add(0, -0.2, 0), false);
            assertTrue(exact(result), describe(result));
            var carry = (JavaPredictionCarry)
                    f.player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
            assertEquals(distanceAfter(version, 0, -0.2), carry.fallDistance(), 1e-10);
        }
    }

    @ParameterizedTest(name = "{0}: an opening box uses the same fall callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aShulkerBoxMoveUsesTheSameFallCallbackGate(String release, ClientVersion version) throws Exception {
        try (var f = falling(version)) {
            var travel = f.tick(START.add(0, -0.2, 0), false);
            assertTrue(exact(travel), describe(travel));
            Vec3 position = START.add(0, -0.2, 0);
            var mover = new ClientBlockEntityPass.ShulkerBox(
                    new SimpleCollisionBox(0, FLOOR_Y + 7, 0, 1, FLOOR_Y + 11, 1), new Vec3(0, -0.25, 0));
            var after = JavaBlockEntityPass.run(
                            f.player,
                            travel.getSimulationContext(),
                            new ClientBlockEntityPass(List.of(mover), 100),
                            state(version, position),
                            f.player.pose,
                            false,
                            START)
                    .state();
            assertEquals(position.y - 0.25, after.position().y, 1e-10);
            assertEquals(distanceAfter(version, distanceAfter(version, 0, -0.2), -0.25), after.fallDistance(), 1e-10);
        }
    }

    @ParameterizedTest(name = "{0}: a downward piston uses the same fall callback")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aPistonMoveUsesTheSameFallCallbackGate(String release, ClientVersion version) throws Exception {
        try (var f = falling(version)) {
            var travel = f.tick(START.add(0, -0.2, 0), false);
            assertTrue(exact(travel), describe(travel));
            Vec3 position = START.add(0, -0.2, 0);
            var mover = new ClientBlockEntityPass.Piston(
                    new BlockPos(0, FLOOR_Y + 9, 0), BlockIds.STONE.defaultState(), Direction.DOWN, true, false, 0);
            var after = JavaBlockEntityPass.run(
                            f.player,
                            travel.getSimulationContext(),
                            new ClientBlockEntityPass(List.of(mover), 100),
                            state(version, position),
                            f.player.pose,
                            false,
                            START)
                    .state();
            // The advancing stone face ends at Y=73.5; the player's head ends 0.01 below it.
            double expectedY = FLOOR_Y + 9.5 - (double) 1.8F - 0.01;
            assertEquals(expectedY, after.position().y, 1e-10);
            assertEquals(
                    distanceAfter(version, distanceAfter(version, 0, -0.2), expectedY - position.y),
                    after.fallDistance(),
                    1e-10);
        }
    }

    private static JavaTickFixture falling(ClientVersion version) throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
        f.place(START, new Vec3(0, -0.2, 0));
        f.input(NONE);
        return f;
    }

    private static JavaPassState state(ClientVersion version, Vec3 position) {
        return new JavaPassState(
                position,
                new Vec3(0, (-0.2 - 0.08) * 0.98F, 0),
                false,
                null,
                null,
                distanceAfter(version, 0, -0.2),
                ClientPistonDeltas.NONE);
    }

    private static double distanceAfter(ClientVersion version, double before, double movementY) {
        if (version == ClientVersion.V_1_21_4) return before;
        double after = before - (float) movementY;
        return version.isOlderThan(ClientVersion.V_1_21_5) ? (float) after : after;
    }
}
