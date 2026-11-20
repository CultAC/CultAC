package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaCobwebMovementTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final double FALL = 0.0784000015258789;
    private static final double WALK = (double) 0.98F * (double) 0.1F;
    private static final double FRICTION = 0.6F * 0.91F;

    /**
     * Entity#move skips setPos when the collided movement is at most 1e-7 long, measured on the whole delta that
     * LivingEntity#travel built with moveRelative's input. A web landing whose vertical clip alone is under that
     * bound still moves when the player walks.
     */
    @Test
    void aWebLandingWithInputKeepsItsTinyVerticalClip() throws Exception {
        try (var f = new JavaTickFixture()) {
            web(f, new BlockPos(0, FLOOR_Y, 0));
            double gap = 2.0E-4;
            f.place(new Vec3(0.5, FLOOR_Y + gap + FALL, 0.5), new Vec3(0, -FALL, 0));

            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + gap, 0.5), false));
            f.input(FORWARD);
            // LivingEntity#moveRelative at the flying speed, then the web's 0.25 horizontal stuck multiplier.
            double walked = (double) 0.98F * 0.02F * 0.25;
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5 + walked), true));
        }
    }

    /**
     * Entity#checkInsideBlocks steps the axes in Direction#axisStepOrder of the delta Entity#move recorded, which
     * includes moveRelative's input. Carried X drift with a larger Z input steps Z first, clear of a web the X-first
     * walk would cross at its corner, so the next move is not slowed.
     */
    @Test
    void theInsideBlockWalkOrdersItsAxesByTheInputAppliedDelta() throws Exception {
        try (var f = new JavaTickFixture()) {
            // Corner of the box at x 0.995 -> 1.005 enters the web column only while z still starts below 1.
            web(f, new BlockPos(1, FLOOR_Y + 1, 0));
            double drift = 0.01;
            Vec3 start = new Vec3(0.695, FLOOR_Y + 1, 1.29);
            f.place(start, new Vec3(drift, 0, 0));

            f.input(FORWARD);
            double walked = (double) 0.98F * 0.02F;
            Vec3 first = start.add(new Vec3(drift, 0, walked));
            assertExact(f.tick(first, false));
            f.input(NONE);
            // LivingEntity#travel: air friction 0.91 horizontally, gravity then 0.98 vertically.
            Vec3 second = first.add(new Vec3(drift * 0.91F, -FALL, walked * 0.91F));
            assertExact(f.tick(second, false));
        }
    }

    /**
     * Before 1.21.9, BlockGetter#boxTraverseBlocks (up to 1.21.4) and #forEachBlockIntersectedBetween (from 1.21.5)
     * visit only the end box, deflated by 1.0E-5, for a movement shorter than 0.99999. A walk whose start box overlaps
     * a web and whose end box clears it is not stuck on the next move.
     */
    @ParameterizedTest
    @EnumSource(
            value = ClientVersion.class,
            names = {"V_1_21_2", "V_1_21_4", "V_1_21_5", "V_1_21_6", "V_1_21_7"})
    void aShortWalkOutOfAWebIsNotStuckWhenOnlyTheStartBoxTouchedIt(ClientVersion version) throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", version);
            Vec3 exit = walkOutOfWeb(f);
            Vec3 free = exit.add(new Vec3(0, 0, WALK));
            assertExact(f.tick(free, true));
            assertExact(f.tick(free.add(new Vec3(0, 0, WALK * FRICTION + WALK)), true));
        }
    }

    /**
     * From 1.21.9, BlockGetter#forEachBlockIntersectedBetween sweeps the box from the start position, so a web
     * overlapped only by the start box still sets the stuck multiplier for the next move.
     */
    @Test
    void aSweptWalkOutOfAWebIsStuckWhenOnlyTheStartBoxTouchedIt() throws Exception {
        try (var f = new JavaTickFixture()) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_9);
            Vec3 exit = walkOutOfWeb(f);
            assertExact(f.tick(exit.add(new Vec3(0, 0, WALK * 0.25)), true));
        }
    }

    private static Vec3 walkOutOfWeb(JavaTickFixture f) {
        web(f, new BlockPos(0, FLOOR_Y, -1));
        // Box minZ -0.01 sits inside the web; one stuck walk of 0.0245 takes it to 0.0145.
        Vec3 start = new Vec3(0.5, FLOOR_Y, 0.29);
        f.place(start, new Vec3(0, -FALL, 0));
        f.input(NONE);
        assertExact(f.tick(start, true));
        f.input(FORWARD);
        Vec3 exit = start.add(new Vec3(0, 0, WALK * 0.25));
        assertExact(f.tick(exit, true));
        return exit;
    }

    private static void web(JavaTickFixture f, BlockPos pos) {
        f.player.compensatedWorld.updateBlock(pos, BlockIds.COBWEB.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
