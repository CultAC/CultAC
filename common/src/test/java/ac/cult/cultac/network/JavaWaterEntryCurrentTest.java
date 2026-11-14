package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A client falls into flowing water placed two ticks earlier, beside its source. Not in water at the tick's start, it
 * enters the flowing block during Entity#move, and LivingEntity#checkFallDamage pushes it by the current before
 * LivingEntity#travelInAir's drag. Neither the placed nor the replaced state of the flowing block reaches the box at
 * the tick's start. The source drains before the next tick, which therefore starts in still water.
 */
class JavaWaterEntryCurrentTest {
    private static final ServerboundPlayerInput BACK_JUMP =
            new ServerboundPlayerInput(false, true, false, false, true, false, false);
    private static final BlockPos FLOWING = new BlockPos(4977, 63, 10006);
    private static final BlockPos SOURCE = new BlockPos(4977, 63, 10007);
    private static final BlockPos BESIDE_SOURCE = new BlockPos(4978, 63, 10007);
    private static final Vec3 START = new Vec3(4976.802188507213, 63.796735600668704, 10005.739438291841);
    private static final Vec3 ENTERED = new Vec3(4976.7775013727005, 63.49520087700592, 10005.859693923336);
    private static final Vec3 SWAM = new Vec3(4976.751557549983, 63.16129683964513, 10005.946237668526);
    private static final float ENTRY_YAW = 351.5115051269531F;
    private static final float ENTRY_PITCH = 30.729351043701172F;
    private static final float SWIM_YAW = 349.781005859375F;
    private static final float SWIM_PITCH = 34.40672302246094F;

    @Test
    void theEntryCurrentCarriesIntoTheFirstSwimmingTick() throws Exception {
        try (var f = fixture()) {
            PredictionResult entry = enter(f);
            assertSame(DesyncStatus.FALSE, entry.getSimulationContext().getWorldData().getInWater());
            drainSource(f);
            PredictionResult swim = swim(f, SWAM);
            assertTrue(exact(swim), describe(swim));
        }
    }

    /**
     * A client that adds a second entry current's worth of motion, once the drain is past the block-change timing,
     * needs more input than the water gives.
     */
    @Test
    void aSecondEntryCurrentIsStrafe() throws Exception {
        try (var f = fixture()) {
            enter(f);
            drainSource(f);
            for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertRejected(swim(f, SWAM.add(0.0, 0.0, -0.0036)), "Strafe");
        }
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        for (int x = 309; x <= 312; x++) {
            for (int z = 624; z <= 626; z++) f.player.compensatedWorld.ensureValidationChunkLoaded(x, z);
        }
        for (int x = 4968; x <= 4990; x++) {
            for (int z = 9998; z <= 10014; z++) {
                f.player.compensatedWorld.updateBlock(new BlockPos(x, 62, z), BlockIds.STONE.defaultState());
            }
        }
        int water = BlockIds.WATER.defaultState();
        f.player.compensatedWorld.updateBlock(SOURCE, water);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        int flowing = DataTables.defaults().registry().with(water, "level", "1");
        f.player.compensatedWorld.updateBlock(BESIDE_SOURCE, flowing);
        f.player.compensatedWorld.updateBlock(FLOWING, flowing);
        for (int tick = 0; tick < 2; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return f;
    }

    private static PredictionResult enter(JavaTickFixture f) {
        f.player.xRot = ENTRY_YAW;
        f.player.yRot = ENTRY_PITCH;
        Vec3 moved = ENTERED.subtract(START);
        Vec3 input = ClientMoveInput.of(f, BACK_JUMP, 1.0F, 0.02F, ENTRY_YAW);
        f.place(START, new Vec3(moved.x - input.x, moved.y, moved.z - input.z));
        f.input(BACK_JUMP);
        PredictionResult entry = f.tick(ENTERED, false);
        assertTrue(exact(entry), describe(entry));
        return entry;
    }

    private static void drainSource(JavaTickFixture f) {
        f.player.compensatedWorld.updateBlock(SOURCE, BlockIds.AIR.defaultState());
    }

    private static PredictionResult swim(JavaTickFixture f, Vec3 to) {
        f.player.xRot = SWIM_YAW;
        f.player.yRot = SWIM_PITCH;
        return f.tick(to, false);
    }
}
