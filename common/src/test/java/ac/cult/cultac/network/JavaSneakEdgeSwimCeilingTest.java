package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 1.21.11 sprint-swimmer on the floor of a one-block water gap under a stone ceiling, holding shift into the floor's
 * x = 9 edge, then letting go. Standing does not fit under the ceiling, but LocalPlayer#aiStep never crouches a
 * swimming player, so the input Player#maybeBackOffFromEdge hides keeps the full scale and the release moves on it.
 */
class JavaSneakEdgeSwimCeilingTest {
    private static final ServerboundPlayerInput SWIM =
            new ServerboundPlayerInput(true, false, false, false, false, false, true);
    private static final ServerboundPlayerInput SWIM_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, true);
    private static final ServerboundPlayerInput WADE =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput WADE_SHIFT =
            new ServerboundPlayerInput(true, false, false, false, false, true, false);
    private static final float YAW = -90.0F;
    private static final int RELEASE_TICK = 14;

    @Test
    void aSwimmerHeldAtAnEdgeUnderACeilingMovesOnWithTheFullInput() throws Exception {
        PredictionResult release = holdThenRelease(0.0, false);
        assertTrue(exact(release), describe(release));
    }

    /** The same release moving 0.01 further along x than the hidden velocity and the keys allow. */
    @Test
    void aReleaseBeyondTheHiddenVelocityFlagsSpeed() throws Exception {
        PredictionResult release = holdThenRelease(0.01, false);
        assertSpeed(release);
    }

    /**
     * Eating once the edge holds the swimmer: LocalPlayer#shouldStopSwimSprinting ignores the use, so the swimmer keeps
     * sprinting, and LocalPlayer#modifyInput scales the keys by the item's 0.2F with no sneaking scale.
     */
    @Test
    void aSwimmerEatingAtAnEdgeUnderACeilingMovesOnWithTheItemSlowedInput() throws Exception {
        PredictionResult release = holdThenRelease(0.0, true);
        assertTrue(exact(release), describe(release));
    }

    /**
     * The same eating release 0.02 further along x. Speed's liquid input residual accepts +0.01 for an eating swimmer
     * with no edge involved, so 0.02 is the first step past it.
     */
    @Test
    void anEatingReleaseBeyondTheHiddenVelocityFlagsSpeed() throws Exception {
        PredictionResult release = holdThenRelease(0.02, true);
        assertSpeed(release);
    }

    /**
     * Each tick the edge holds the swimmer starts from the client's deltaMovement, so the next tick gets the carry and
     * no hidden-velocity box.
     */
    @Test
    void everyHeldSwimTickUnderACeilingIsCarried() throws Exception {
        for (boolean eat : new boolean[] {false, true}) {
            List<PredictionResult> held = new ArrayList<>();
            PredictionResult release = holdThenRelease(0.0, eat, held);
            assertTrue(exact(release), describe(release));
            for (int tick = 0; tick < held.size(); tick++) {
                PredictionResult result = held.get(tick);
                assertTrue(result.isSneakEdgeCarried(), "eat " + eat + " held tick " + tick + " " + describe(result));
            }
        }
    }

    /**
     * A player wading under the ceiling is not swimming, so the edge reads ClientKeyInput#travelInput, which leaves the
     * input unknown where standing does not fit: no tick on the edge is carried or counts as hiding nothing.
     */
    @Test
    void aWaderHeldAtAnEdgeUnderACeilingIsNotCarried() throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            gapUnderCeiling(f);
            f.player.xRot = YAW;
            f.player.yRot = 20.0F;
            double x = 8.8;
            double z = 0.5;
            f.player.pose = Pose.SWIMMING;
            f.place(new Vec3(x, FLOOR_Y, z), Vec3.ZERO);
            f.input(WADE);

            Vec3 input = ClientMoveInput.of(f, WADE, 1.0F, 0.02F, YAW);
            double velocityX = 0.0;
            double velocityZ = 0.0;
            int hidden = 0;
            int onEdge = 0;
            for (int tick = 0; tick < RELEASE_TICK; tick++) {
                boolean shift = tick >= 3;
                f.input(shift ? WADE_SHIFT : WADE);
                double deltaX = velocityX + input.x;
                double movedX = deltaX;
                while (shift && movedX != 0.0 && x - 0.3 + 1.0E-7 + movedX >= 9.0) {
                    movedX = Math.abs(movedX) <= 0.05 ? 0.0 : movedX - 0.05;
                }
                x += movedX;
                z += velocityZ + input.z;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                if (shift && movedX == 0.0) hidden++;
                if (shift && result.getSimulationContext().getWorldData().getSneak().isOnEdge()) {
                    onEdge++;
                    assertFalse(result.isSneakEdgeCarried(), "tick " + tick + " " + describe(result));
                    assertFalse(result.isSneakEdgeExact(), "tick " + tick + " " + describe(result));
                }
                // LivingEntity#travelInWater slows a player that is not sprinting by getWaterSlowDown's 0.8F.
                velocityX = deltaX * 0.8F;
                velocityZ = (velocityZ + input.z) * 0.8F;
            }
            assertTrue(hidden >= 3, "hidden ticks " + hidden);
            assertTrue(onEdge > hidden, "ticks on the edge " + onEdge);
        }
    }

    private static void assertSpeed(PredictionResult release) {
        assertTrue(
                release.getFlags().stream().anyMatch(flag -> flag.getCheck().getCheckName().equals("Speed")),
                describe(release));
    }

    /**
     * Swims at the edge, holds shift from tick 3 and releases at {@link #RELEASE_TICK}, {@code extraX} further; when
     * {@code eat}, starts eating on the tick after the edge first holds the swimmer, and releases, still eating, after
     * the edge has held it two eating ticks.
     */
    private static PredictionResult holdThenRelease(double extraX, boolean eat) throws Exception {
        return holdThenRelease(extraX, eat, new ArrayList<>());
    }

    /** {@link #holdThenRelease(double, boolean)}, adding to {@code held} each tick the edge held the swimmer. */
    private static PredictionResult holdThenRelease(double extraX, boolean eat, List<PredictionResult> held)
            throws Exception {
        try (var f = new JavaTickFixture(ProtocolVersion.V1_21_11, ClientVersion.V_1_21_11)) {
            var food = ItemUtil.modelItems().stack("minecraft:golden_apple", 1);
            f.player.getInventory().inventory.getInventoryStorage().setItem(Inventory.SLOT_OFFHAND, food);
            gapUnderCeiling(f);
            f.player.xRot = YAW;
            f.player.yRot = 20.0F;
            double x = 8.8;
            double z = 0.5;
            f.player.pose = Pose.SWIMMING;
            f.place(new Vec3(x, FLOOR_Y, z), Vec3.ZERO);
            f.input(SWIM);
            var sprint = new ServerboundPlayerCommand(f.player.entityID, PlayerCommandAction.START_SPRINTING, 0);
            new PacketEntityAction()
                    .onPlayerCommand(f.event(ServerboundPackets.PLAYER_COMMAND, sprint), f.player, sprint);

            Vec3 input = ClientMoveInput.of(f, SWIM, 1.0F, 0.02F, YAW);
            Vec3 eatingInput = ClientMoveInput.of(f, SWIM, 0.2F, 0.02F, YAW);
            double velocityX = 0.0;
            double velocityZ = 0.0;
            int hidden = 0;
            int hiddenEating = 0;
            boolean eating = false;
            for (int tick = 0; tick < RELEASE_TICK; tick++) {
                boolean shift = tick >= 3;
                if (eat && !eating && hidden > 0) {
                    f.player.clientItemUse.start(Hand.OFF_HAND, food, ClientItemUseState.duration(food), true);
                    eating = true;
                    input = eatingInput;
                }
                f.input(shift ? SWIM_SHIFT : SWIM);
                double deltaX = velocityX + input.x;
                double movedX = deltaX;
                // Player#maybeBackOffFromEdge steps the move back by 0.05 while the deflated box could fall.
                while (shift && movedX != 0.0 && x - 0.3 + 1.0E-7 + movedX >= 9.0) {
                    movedX = Math.abs(movedX) <= 0.05 ? 0.0 : movedX - 0.05;
                }
                boolean edgeHeld = shift && movedX == 0.0;
                if (edgeHeld) {
                    hidden++;
                    if (eating) hiddenEating++;
                }
                x += movedX;
                z += velocityZ + input.z;
                PredictionResult result = f.tick(new Vec3(x, FLOOR_Y, z), true);
                assertTrue(exact(result), "tick " + tick + " " + describe(result));
                if (edgeHeld) held.add(result);
                // LivingEntity#travelInWater slows a sprinting player by 0.9F.
                velocityX = deltaX * 0.9F;
                velocityZ = (velocityZ + input.z) * 0.9F;
                if (eat && hiddenEating == 2) break;
            }
            assertTrue(hidden >= 3, "hidden ticks " + hidden);
            if (eat) assertTrue(hiddenEating == 2, "hidden eating ticks " + hiddenEating);

            f.input(SWIM);
            double deltaX = velocityX + input.x;
            return f.tick(new Vec3(x + deltaX + extraX, FLOOR_Y, z + velocityZ + input.z), true);
        }
    }

    /** Water one block deep over the floor, which ends at x = 9 above a water-filled drop, under a stone ceiling. */
    private static void gapUnderCeiling(JavaTickFixture f) {
        for (int x = -8; x <= 12; x++) {
            for (int z = -8; z <= 8; z++) {
                water(f, x, FLOOR_Y, z);
                f.player.compensatedWorld.updateBlock(new BlockPos(x, FLOOR_Y + 1, z), BlockIds.STONE.defaultState());
                if (x >= 9) {
                    water(f, x, FLOOR_Y - 1, z);
                    water(f, x, FLOOR_Y - 2, z);
                }
            }
        }
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void water(JavaTickFixture f, int x, int y, int z) {
        f.player.compensatedWorld.updateBlock(new BlockPos(x, y, z), BlockIds.WATER.defaultState());
    }
}
