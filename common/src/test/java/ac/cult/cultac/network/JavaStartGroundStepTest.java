package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.manager.player.setback.CorrectionBuilder;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.data.SetbackPosWithVector;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A correction ticks once from the safe position against a bottom slab, with a delta movement that has no vertical
 * part, as a set_entity_motion without one leaves it. Entity#collide steps when Entity#onGround holds as the move
 * starts, so from the ground the tick climbs the slab; from the air it stops at the slab's side.
 */
class JavaStartGroundStepTest {
    private static final Vec3 PUSH = new Vec3(0.3, 0.0, 0.0);
    /** The box's +x face 0.01 short of the slab at x = 1. */
    private static final Vec3 SHORT_OF_THE_SLAB = new Vec3(0.69, FLOOR_Y, 0.5);
    /** The box's +x face on the slab's side. */
    private static final Vec3 AT_THE_SLAB = new Vec3(0.7, FLOOR_Y, 0.5);

    @Test
    void aCorrectionTickFromTheGroundClimbsTheSlab() throws Exception {
        try (var f = start(SHORT_OF_THE_SLAB, true)) {
            Vec3 location = correction(f, SHORT_OF_THE_SLAB, false).getTeleportData().getLocation();
            assertEquals(0.99, location.x, 1.0E-9, location.toString());
            assertEquals(FLOOR_Y + 0.5, location.y, 1.0E-9, location.toString());
        }
    }

    @Test
    void aCorrectionTickFromTheAirStopsAtTheSlab() throws Exception {
        try (var f = start(SHORT_OF_THE_SLAB, false)) {
            Vec3 location = correction(f, SHORT_OF_THE_SLAB, false).getTeleportData().getLocation();
            assertEquals(0.7, location.x, 1.0E-7, location.toString());
            assertEquals(FLOOR_Y, location.y, 1.0E-9, location.toString());
        }
    }

    @Test
    void aCorrectionAgainstTheSlabFromTheGroundMoves() throws Exception {
        try (var f = start(AT_THE_SLAB, true)) {
            assertNotNull(correction(f, AT_THE_SLAB, true), "the grounded tick steps onto the slab");
        }
    }

    @Test
    void aCorrectionAgainstTheSlabFromTheAirBarelyMoves() throws Exception {
        try (var f = start(AT_THE_SLAB, false)) {
            assertNull(correction(f, AT_THE_SLAB, true), "the airborne tick stays at the slab's side");
        }
    }

    /** A bottom slab at x = 1 and one committed standing tick at {@code at}, starting {@code onGround}. */
    private static JavaTickFixture start(Vec3 at, boolean onGround) throws Exception {
        var f = new JavaTickFixture();
        var registry = DataTables.defaults().registry();
        int slab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "bottom");
        f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), slab);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff();
        f.place(at, Vec3.ZERO);
        // The tick's ground state lives in the simulation context.
        f.player.lastOnGround = false;
        f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.fromBoolean(onGround));
        f.tick(at, onGround);
        return f;
    }

    /** The correction ticking {@link #PUSH} from {@code safe} in place of the latest movement. */
    private static SetBackData correction(JavaTickFixture f, Vec3 safe, boolean ignoreUnchanged) {
        return new CorrectionBuilder(f.player)
                .build(
                        new CorrectionBuilder.Request(true, ignoreUnchanged, null),
                        new CorrectionBuilder.Inputs(
                                new SetbackPosWithVector(safe, PUSH), null, Integer.MIN_VALUE, 0, true, true, false));
    }
}
