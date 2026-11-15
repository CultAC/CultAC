package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JavaClientTickEndCarryTest {
    /**
     * sendPosition compares against xLast, while every withheld travel still changes the physical position.
     * No-gravity metadata and a low sneaking_speed produce small, non-exempt travels; aiStep resets their
     * sub-0.003 carry before each moveRelative. Entity#move commits them because no axis is clipped.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void smallTravelsAccumulateBeforeTheNextPositionReport(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 start = new Vec3(0.5, FLOOR_Y + 3, 0.5);
            f.player.compensatedEntities.getSelf().hasGravity = false;
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID, List.of(new AttributeSnapshot("minecraft:sneaking_speed", 0.0025, List.of())));
            f.place(start, Vec3.ZERO);
            f.input(new ServerboundPlayerInput(false, false, false, false, false, true, false));
            assertTrue(JavaTickFixture.exact(f.tick(start, false)));
            var keys = new ServerboundPlayerInput(true, false, false, false, false, true, false);
            f.input(keys);
            Vec3 step = ClientMoveInput.of(f, keys, 0.0025F, 0.02F, 0);
            assertTrue(step.length() * 4 < 0.0002 && step.length() * 5 > 0.0002);
            for (int cycle = 0; cycle < 3; cycle++) {
                for (int tick = 0; tick < 4; tick++) {
                    f.tickEnd();
                    var prediction =
                            f.player.checkManager.getSimulationProcessor().getLastPrediction();
                    assertTrue(JavaTickFixture.exact(prediction), JavaTickFixture.describe(prediction));
                    assertEquals(
                            start.z + step.z * (cycle * 5 + tick + 1),
                            f.player.z,
                            1.0E-12,
                            "withheld tick " + tick + ": " + JavaTickFixture.describe(prediction));
                }
                var result = f.tick(start.add(step.scale((cycle + 1) * 5)), false);
                assertTrue(JavaTickFixture.exact(result), JavaTickFixture.describe(result));
            }
        }
    }

    /** moveRelative can nearly cancel a carried velocity even when the ordinary input itself exceeds the report bound. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWithheldTravelPreservesTheRemainderOfOpposingInput(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedEntities.getSelf().hasGravity = false;
            Vec3 at = new Vec3(0.5, FLOOR_Y + 3, 0.5);
            f.place(at, Vec3.ZERO);
            assertTrue(JavaTickFixture.exact(f.tick(at, false)));
            var keys = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            Vec3 input = ClientMoveInput.of(f, keys, 1.0F, 0.02F, 0);
            f.player.checkManager.getSimulationProcessor().seedStartingVelocity(new Vec3(0, 0, -input.z + 0.00005));
            f.input(keys);
            f.tickEnd();
            var withheld = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            assertTrue(JavaTickFixture.exact(withheld), JavaTickFixture.describe(withheld));
            assertEquals(at.z + 0.00005, f.player.z, 1.0E-12);
            // aiStep clears the small carry before the next ordinary input; the physical remainder stays.
            var next = f.tick(at.add(0, 0, 0.00005 + input.z), false);
            assertTrue(JavaTickFixture.exact(next), JavaTickFixture.describe(next));
        }
    }

    /** Entity#move does not setPos for a tiny horizontal travel whose attempted fall is clipped by the floor. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aClippedFallDoesNotAccumulateTinyGroundMovement(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            f.player.compensatedEntities.updateAttributes(
                    f.player.entityID, List.of(new AttributeSnapshot("minecraft:movement_speed", 0.00005, List.of())));
            Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(at, new Vec3(0, -0.0784000015258789, 0));
            assertTrue(JavaTickFixture.exact(f.tick(at, true)));
            f.input(new ServerboundPlayerInput(true, false, false, false, false, false, false));
            for (int tick = 0; tick < 8; tick++) {
                f.tickEnd();
                var prediction = f.player.checkManager.getSimulationProcessor().getLastPrediction();
                assertTrue(JavaTickFixture.exact(prediction), JavaTickFixture.describe(prediction));
                assertEquals(at.z, f.player.z);
            }
        }
    }

    /** Minecraft#tick sends CLIENT_TICK_END in every supported release, including idle client ticks. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ac.cult.cultac.network.JavaPistonReleaseCases#releases")
    void aWithheldPositionDoesNotInventASkippedTravel(String release, ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 position = new Vec3(0.5, FLOOR_Y, 0.5);
            f.place(position, new Vec3(0, -0.0784000015258789, 0));
            f.tick(position, true);
            for (int tick = 0; tick < 25; tick++) f.tickEnd();
            var last = f.player.checkManager.getSimulationProcessor().getLastPrediction();
            Vec3 carried = new Vec3(0.12, 0.2, 0.3);
            var candidates = JavaMovementEngine.INSTANCE.applyModifiers(
                    new MovementModifiers(), f.player, Set.of(carried), last.getSimulationContext(), last);
            assertEquals(1, candidates.size());
            assertEquals(carried, new Vec3(candidates.getFirst().x, candidates.getFirst().y, candidates.getFirst().z));
        }
    }
}
