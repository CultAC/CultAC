package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A shift-holding player eating at the floor's edge. LocalPlayer#modifyInput scales the keys by the item's 0.2F before
 * the sneaking 0.3F, Player#maybeBackOffFromEdge holds the player at the edge, and deltaMovement keeps the slowed input
 * the edge took. On the tick shift is released LocalPlayer#aiStep still crouches from the previous keys, so the move is
 * that hidden velocity plus the slowed input, and no edge backs it off.
 */
class JavaSneakEdgeItemUseTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_RIGHT =
            new ServerboundPlayerInput(false, false, false, true, false, true, false);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.299.
    private static final double EDGE_X = 9.299;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double EATING_SNEAK_INPUT = (double) (0.98F * 0.2F * 0.3F) * (double) 0.1F;
    private static final double WALK_INPUT = (double) 0.98F * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final int EDGE_TICKS = 6;

    @Test
    void releasingShiftWhileEatingReleasesTheVelocityTheEdgeHid() throws Exception {
        try (var f = new JavaTickFixture()) {
            double hidden = sneakAtTheEdgeWhileEating(f);
            f.input(LEFT);
            PredictionResult release = f.tick(new Vec3(EDGE_X + hidden + EATING_SNEAK_INPUT, FLOOR_Y, 0.5), true);
            assertTrue(exact(release), describe(release));
            assertEquals(hidden, release.getInitialStartingVel().x, 1.0E-12);
        }
    }

    @Test
    void walkingAwayFromTheEdgeAtFullSpeedWhileSneakingIsSneakSlowed() throws Exception {
        try (var f = new JavaTickFixture()) {
            double hidden = sneakAtTheEdgeWhileEating(f);
            f.input(SNEAK_RIGHT);
            double velocity = hidden;
            double x = EDGE_X;
            boolean slowed = false;
            for (int tick = 0; tick < 3; tick++) {
                x += velocity - WALK_INPUT;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, 0.5), true);
                slowed |= sneakSlowed(result);
                velocity = (velocity - WALK_INPUT) * GROUND_FRICTION;
            }
            assertTrue(slowed, "walking away at full speed while sneaking");
        }
    }

    /**
     * A use the server may have refused leaves LocalPlayer#modifyInput's multiplier at the item's or at 1.0. At the
     * edge both inputs back off to the same held position with different deltaMovement, so neither is carried, and the
     * release on the item's hidden velocity is accepted without NoSneakSlow.
     */
    @Test
    void anEdgeHeldThroughAnUncertainUseCarriesNeitherMultipliersVelocity() throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, GROUNDED_Y, 0));
            f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), false);
            f.input(SNEAK);
            assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));

            f.input(SNEAK_LEFT);
            double velocity = 0.0;
            for (int tick = 0; tick < EDGE_TICKS; tick++) {
                assertExact(tick, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
                for (Vec3 carried : f.player.checkManager.getSimulationProcessor().getValidPlayerStartingVels()) {
                    assertEquals(0.0, carried.x, "tick " + tick + " " + carried);
                }
                velocity = (velocity + EATING_SNEAK_INPUT) * GROUND_FRICTION;
            }

            f.input(LEFT);
            PredictionResult release = f.tick(new Vec3(EDGE_X + velocity + EATING_SNEAK_INPUT, FLOOR_Y, 0.5), true);
            assertTrue(exact(release), describe(release));
            assertFalse(sneakSlowed(release), describe(release));
        }
    }

    private static boolean sneakSlowed(PredictionResult result) {
        return !result.isExempt()
                && result.getFlags().stream()
                        .anyMatch(flag -> flag.getCheck().getCheckName().equals("NoSneakSlow"));
    }

    /** Holds the player at the edge, eating and sneaking left; returns the x velocity the edge hid. */
    private static double sneakAtTheEdgeWhileEating(JavaTickFixture f) {
        var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
        f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
        f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, GROUNDED_Y, 0));
        f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
        f.input(SNEAK);
        assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));

        f.input(SNEAK_LEFT);
        double velocity = 0.0;
        for (int tick = 0; tick < EDGE_TICKS; tick++) {
            assertExact(tick, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
            velocity = (velocity + EATING_SNEAK_INPUT) * GROUND_FRICTION;
        }
        return velocity;
    }

    private static void assertExact(int tick, PredictionResult result) {
        assertTrue(exact(result), "tick " + tick + " " + describe(result));
    }
}
