package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** Before 26.3, LocalPlayer#sendPosition runs before Level#tickBlockEntities shoves the player. */
class JavaShulkerBoxPushTest {
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final BlockPos BOX = new BlockPos(0, FLOOR_Y - 1, 0);

    @Test
    void aPlayerOnAnOpeningShulkerBoxIsShovedEachTick() throws Exception {
        try (var f = fixture()) {
            int state = box(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            open(f, state);

            // Each tick: LocalPlayer lands on the lid ShulkerBoxBlockEntity#getBoundingBox
            // raised by 0.05, then moveCollidedEntities shoves it 0.11 above that.
            for (int tick = 0; tick <= 10; tick++) {
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.05 * tick, 0.5), true));
            }
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.5, 0.5), true));
        }
    }

    @Test
    void theShoveDoesNotHoldThePlayerAboveTheLid() throws Exception {
        try (var f = fixture()) {
            int state = box(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            open(f, state);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            // Shoved to .11, the player falls back onto the lid at .05.
            PredictionResult hover = f.tick(new Vec3(0.5, FLOOR_Y + shove(0.0F, 0.1F), 0.5), false);
            assertFalse(exact(hover), describe(hover));
        }
    }

    /** 26.3 sends the movement after Level#tickBlockEntities, so each packet already holds the shove. */
    @Test
    void a26_3PlayerReportsTheShoveInTheSameTick() throws Exception {
        try (var f = new JavaTickFixture()) {
            int state = box(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            open(f, state);

            // Travel lands on the lid at .05 * tick, then the shove leaves the player in the air.
            for (int tick = 0; tick < 10; tick++) {
                double y = 0.05 * tick + shove(0.1F * tick, 0.1F * (tick + 1));
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y + y, 0.5), false));
            }
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.5, 0.5), true));
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.5, 0.5), true));
        }
    }

    @Test
    void aPlayerOnAClosingShulkerBoxFollowsTheLidDown() throws Exception {
        try (var f = fixture()) {
            int state = box(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            open(f, state);
            for (int tick = 0; tick <= 10; tick++) f.tick(new Vec3(0.5, FLOOR_Y + 0.05 * tick, 0.5), true);
            close(f, state);

            // CLOSING never moves entities; each fall lands on the lid lowered by 0.05.
            for (int tick = 0; tick <= 10; tick++) {
                assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.5 - 0.05 * tick, 0.5), true));
            }
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
        }
    }

    /** Issue #86: a server correction leaves the player above the lid; it falls back through further shoves. */
    @Test
    void aPlayerFallingOntoAnOpeningShulkerBoxIsShovedBeforeEachMove() throws Exception {
        try (var f = fixture()) {
            int state = box(f);
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), new Vec3(0, -0.0784000015258789, 0));
            f.input(NONE);
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            open(f, state);

            // Progress 0 -> .1 shoves 0.11 after this tick's packet.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y, 0.5), true));
            // Lands on the lid at .05; progress .1 -> .2 shoves 0.11 again.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.05, 0.5), true));
            // The server, whose lid led by the ping, corrects to its own shoved position.
            double served = 0.33000001192092;
            f.teleport(new Vec3(0.5, FLOOR_Y + served, 0.5), new RelativeFlag(0));
            // Progress .2 -> .3: the lid delta [.2, .3] is below the player's feet.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + served, 0.5), false));
            double fall = -0.0784000015258789;
            double y = served + fall;
            // Progress .3 -> .4: the lid delta [.3, .4] reaches the feet and shoves 0.11.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + y, 0.5), false));
            fall = (fall - 0.08) * 0.98;
            y += shove(0.3F, 0.4F) + fall;
            // Progress .4 -> .5 shoves again; the fall then lands on the lid at .25.
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + y, 0.5), false));
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.25, 0.5), true));
            assertExact(f.tick(new Vec3(0.5, FLOOR_Y + 0.30, 0.5), true));
        }
    }

    // ShulkerBoxBlockEntity#moveCollidedEntities: the full-peek delta AABB height, plus 0.01.
    private static double shove(float progressOld, float progress) {
        return (1.0 + progress) - (1.0 + progressOld) + 0.01;
    }

    private static JavaTickFixture fixture() throws Exception {
        var f = new JavaTickFixture();
        JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_26_1);
        return f;
    }

    private static int box(JavaTickFixture f) {
        var registry = DataTables.defaults().registry();
        int state = registry.block("minecraft:shulker_box").defaultState();
        f.player.compensatedWorld.updateBlock(BOX, state);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        return state;
    }

    private static void open(JavaTickFixture f, int state) {
        event(f, state, 1);
    }

    private static void close(JavaTickFixture f, int state) {
        event(f, state, 0);
    }

    /** ShulkerBoxBlockEntity#triggerEvent(1, viewers), applied at its proving ping. */
    private static void event(JavaTickFixture f, int state, int viewers) {
        var world = f.player.compensatedWorld;
        world.applyClientBlockEvent(
                "minecraft:overworld",
                new ClientboundBlockEvent(
                        BOX, 1, viewers, DataTables.defaults().registry().blockIndex(state)));
    }

    private static void assertExact(PredictionResult result) {
        assertTrue(exact(result), describe(result));
    }
}
