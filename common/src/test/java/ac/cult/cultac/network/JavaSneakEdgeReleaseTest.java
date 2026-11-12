package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.data.ItemUseInventory;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tick a player held at a sneaking edge releases shift. Player#maybeBackOffFromEdge keeps the held moves at the edge
 * while deltaMovement keeps each tick's input carried at the medium's friction, so the release moves by that carry plus
 * the release tick's input, and no faster. A hold carried exactly from its first held tick leaves the release no hidden
 * velocity to explain a faster move; a hold whose start the engine could not carry keeps the hidden-velocity box.
 */
class JavaSneakEdgeReleaseTest {
    private static final ServerboundPlayerInput SNEAK =
            new ServerboundPlayerInput(false, false, false, false, false, true, false);
    private static final ServerboundPlayerInput SNEAK_LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, true, false);
    private static final ServerboundPlayerInput LEFT =
            new ServerboundPlayerInput(false, false, true, false, false, false, false);
    private static final ServerboundPlayerInput SWIM =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SWIM_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    // JavaTickFixture's floor ends at x = 9; the player's box overhangs it by 0.299.
    private static final double EDGE_X = 9.299;
    private static final double GROUNDED_Y = -0.0784000015258789;
    private static final double SNEAK_INPUT = (double) (0.98F * 0.3F) * (double) 0.1F;
    private static final double EATING_SNEAK_INPUT = (double) (0.98F * 0.2F * 0.3F) * (double) 0.1F;
    private static final double GROUND_FRICTION = 0.6F * 0.91F;
    private static final int EDGE_TICKS = 6;
    private static final int CONFIRMED_AT = 3;

    private enum Use {
        NONE,
        CERTAIN,
        UNCERTAIN,
        // Starts uncertain; the server's use flag confirms it on held tick CONFIRMED_AT.
        CONFIRMED_MID_HOLD
    }

    private enum Eat {
        NEVER,
        AFTER_FIRST_HELD_TICK,
        EVERY_HELD_TICK
    }

    @Test
    void landReleaseAtTheCarryIsExact() throws Exception {
        assertExactRelease(land(Use.NONE, 0.0));
    }

    @Test
    void landReleasePastTheCarryIsFlagged() throws Exception {
        assertFlaggedRelease(land(Use.NONE, 0.01));
    }

    @Test
    void eatingLandReleaseAtTheCarryIsExact() throws Exception {
        assertExactRelease(land(Use.CERTAIN, 0.0));
    }

    @Test
    void eatingLandReleasePastTheCarryIsFlagged() throws Exception {
        assertFlaggedRelease(land(Use.CERTAIN, 0.03));
    }

    @Test
    void uncertainUseLandReleaseAtTheEatingVelocityIsExact() throws Exception {
        assertExactRelease(land(Use.UNCERTAIN, 0.0));
    }

    @Test
    void sprintSwimReleaseAtTheCarryIsExact() throws Exception {
        assertExactRelease(swim(Eat.NEVER, 0.0));
    }

    @Test
    void eatingSprintSwimReleaseAtTheCarryIsExact() throws Exception {
        assertExactRelease(swim(Eat.AFTER_FIRST_HELD_TICK, 0.0));
        assertExactRelease(swim(Eat.EVERY_HELD_TICK, 0.0));
    }

    @Test
    void eatingSprintSwimReleasePastTheCarryIsFlagged() throws Exception {
        assertFlaggedRelease(swim(Eat.AFTER_FIRST_HELD_TICK, 0.02));
        assertFlaggedRelease(swim(Eat.EVERY_HELD_TICK, 0.02));
    }

    @Test
    void landHoldIsCarriedFromItsFirstHeldTick() throws Exception {
        List<PredictionResult> held = new ArrayList<>();
        assertExactRelease(land(Use.NONE, 0.0, held));
        for (int tick = 0; tick < held.size(); tick++) {
            assertTrue(held.get(tick).isSneakEdgeCarried(), "tick " + tick + " " + describe(held.get(tick)));
        }
    }

    @Test
    void eatingSprintSwimHoldIsCarried() throws Exception {
        for (Eat eat : Eat.values()) {
            List<PredictionResult> held = new ArrayList<>();
            assertExactRelease(swim(eat, 0.0, held));
            assertTrue(!held.isEmpty(), eat.name());
            for (int tick = 0; tick < held.size(); tick++) {
                PredictionResult result = held.get(tick);
                assertTrue(result.isSneakEdgeCarried(), eat + " held tick " + tick + " " + describe(result));
            }
        }
    }

    @Test
    void useConfirmedMidHoldReleaseIsExactAndLeavesItsHoldUncarried() throws Exception {
        List<PredictionResult> held = new ArrayList<>();
        assertExactRelease(land(Use.CONFIRMED_MID_HOLD, 0.0, held));
        // The uncertain ticks have no carry, so no later held tick starts from the client's deltaMovement.
        for (int tick = 0; tick < held.size(); tick++) {
            assertFalse(held.get(tick).isSneakEdgeCarried(), "tick " + tick + " " + describe(held.get(tick)));
        }
    }

    private static void assertExactRelease(PredictionResult release) {
        assertTrue(exact(release), describe(release));
    }

    private static void assertFlaggedRelease(PredictionResult release) {
        assertFalse(exact(release), describe(release));
    }

    /** Holds the player at the floor's edge sneaking left, then releases shift {@code past} beyond vanilla's move. */
    private static PredictionResult land(Use use, double past) throws Exception {
        return land(use, past, new ArrayList<>());
    }

    private static PredictionResult land(Use use, double past, List<PredictionResult> held) throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(EDGE_X, FLOOR_Y, 0.5), new Vec3(0, GROUNDED_Y, 0));
            if (use != Use.NONE) {
                var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
                f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
                f.player.clientItemUse.start(
                        Hand.OFF_HAND, food, ClientItemUseState.duration(food), use == Use.CERTAIN);
            }
            double input = use == Use.NONE ? SNEAK_INPUT : EATING_SNEAK_INPUT;
            f.input(SNEAK);
            assertExact(-1, f.tick(new Vec3(EDGE_X, FLOOR_Y, 0.5), true));
            f.input(SNEAK_LEFT);
            double velocity = 0.0;
            for (int tick = 0; tick < EDGE_TICKS; tick++) {
                if (use == Use.CONFIRMED_MID_HOLD && tick == CONFIRMED_AT)
                    ItemUseInventory.metadata(f.player, (byte) 3);
                // LocalPlayer#sendPosition sends no position for a held tick the back-off keeps at the edge.
                PredictionResult result = unsentTick(f);
                assertExact(tick, result);
                held.add(result);
                velocity = (velocity + input) * GROUND_FRICTION;
            }
            f.input(LEFT);
            return f.tick(new Vec3(EDGE_X + velocity + input + past, FLOOR_Y, 0.5), true);
        }
    }

    /**
     * Sprint-swims along +x over a pool floor that drops at x = 9, holds shift against the drop for three held ticks,
     * then releases shift {@code past} beyond vanilla's move.
     */
    private static PredictionResult swim(Eat eat, double past) throws Exception {
        return swim(eat, past, new ArrayList<>());
    }

    private static PredictionResult swim(Eat eat, double past, List<PredictionResult> heldResults) throws Exception {
        try (var f = new JavaTickFixture()) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            for (int x = -8; x <= 12; x++) {
                for (int z = -8; z <= 8; z++) {
                    for (int y = FLOOR_Y; y <= FLOOR_Y + 3; y++) water(f, x, y, z);
                    if (x >= 9) {
                        water(f, x, FLOOR_Y - 1, z);
                        water(f, x, FLOOR_Y - 2, z);
                    }
                }
            }
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            float yaw = -90.0F;
            f.player.xRot = yaw;
            f.player.yRot = 20.0F;
            double x = 8.8;
            double z = 0.5;
            f.place(new Vec3(x, FLOOR_Y, z), Vec3.ZERO);
            f.input(SWIM);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            Vec3 input = ClientMoveInput.of(f, SWIM, 1.0F, 0.02F, yaw);
            Vec3 eatingInput = ClientMoveInput.of(f, SWIM, 0.2F, 0.02F, yaw);
            double velocityX = 0.0;
            double velocityZ = 0.0;
            double sentX = x;
            double sentZ = z;
            int held = 0;
            boolean eating = false;
            for (int tick = 0; tick < 60 && held < 3; tick++) {
                boolean shift = tick >= 3;
                boolean startEating = eat == Eat.AFTER_FIRST_HELD_TICK ? held > 0 : eat == Eat.EVERY_HELD_TICK && shift;
                if (!eating && startEating) {
                    f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
                    eating = true;
                    input = eatingInput;
                }
                f.input(shift ? SWIM_SHIFT : SWIM);
                double deltaX = velocityX + input.x;
                double movedX = shift ? backOff(x, deltaX) : deltaX;
                if (shift && movedX == 0.0) held++;
                x += movedX;
                z += velocityZ + input.z;
                PredictionResult result;
                if ((x - sentX) * (x - sentX) + (z - sentZ) * (z - sentZ) > 4.0E-8) {
                    result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                    sentX = x;
                    sentZ = z;
                } else {
                    result = unsentTick(f);
                }
                assertExact(tick, result);
                if (shift && movedX == 0.0) heldResults.add(result);
                velocityX = deltaX * 0.9F;
                velocityZ = (velocityZ + input.z) * 0.9F;
            }
            f.input(SWIM);
            return f.tick(new Vec3(x + velocityX + input.x + past, FLOOR_Y, z + velocityZ + input.z), true);
        }
    }

    /** Player#maybeBackOffFromEdge along +x for a box at {@code x} over the drop at x = 9. */
    private static double backOff(double x, double deltaX) {
        double movedX = deltaX;
        while (movedX != 0.0 && x - 0.3 + 1.0E-7 + movedX >= 9.0) {
            movedX = Math.abs(movedX) <= 0.05 ? 0.0 : movedX - 0.05;
        }
        return movedX;
    }

    /** A client tick that sends no position: the engine replays it from CLIENT_TICK_END. */
    private static PredictionResult unsentTick(JavaTickFixture f) {
        f.tickEnd();
        return f.player.checkManager.getSimulationProcessor().getLastPrediction();
    }

    private static void water(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
    }

    private static void assertExact(int tick, PredictionResult result) {
        assertTrue(exact(result), "tick " + tick + " " + describe(result));
    }
}
