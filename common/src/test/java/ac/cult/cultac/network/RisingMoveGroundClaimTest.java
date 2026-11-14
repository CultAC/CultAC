package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static ac.cult.cultac.network.JavaTickFixture.assertRejected;
import static ac.cult.cultac.network.JavaTickFixture.describe;
import static ac.cult.cultac.network.JavaTickFixture.exact;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Entity#move sets onGround from verticalCollisionBelow, which needs a downward requested move: a move that only
 * rises never grounds the player, whatever it ends beside.
 */
class RisingMoveGroundClaimTest {
    private static final int STONE_SLAB =
            DataTables.defaults().registry().block("minecraft:stone_slab").defaultState();
    private static final ServerboundPlayerInput NONE =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);
    private static final ServerboundPlayerInput FORWARD =
            new ServerboundPlayerInput(true, false, false, false, false, false, false);
    private static final ServerboundPlayerInput JUMP =
            new ServerboundPlayerInput(false, false, false, false, true, false, false);
    private static final ServerboundPlayerInput FORWARD_JUMP =
            new ServerboundPlayerInput(true, false, false, false, true, false, false);
    /** Facing +x. */
    private static final float EAST = -90.0F;
    private static final float STONE_FRICTION = 0.6F * 0.91F;
    /** LivingEntity#getFrictionInfluencedSpeed on stone at 0.1 movement speed. */
    private static final float GROUND_SPEED = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));

    @Test
    void aGroundClaimOnARisingTickBesideALedgeTopFlags() throws Exception {
        try (var f = ledge(BlockIds.STONE.defaultState(), new Vec3(0.45, FLOOR_Y, 0.5))) {
            f.input(FORWARD_JUMP);
            Vec3 at = new Vec3(0.45, FLOOR_Y, 0.5);
            double vx = 0.0;
            double vy = 0.42F;
            PredictionResult result = null;
            for (int tick = 0; tick < 3; tick++) {
                boolean ground = tick == 0;
                double dx = vx + ClientMoveInput.of(f, FORWARD_JUMP, 1.0F, ground ? 0.1F : 0.02F, EAST).x;
                at = at.add(dx, vy, 0.0);
                boolean claim = tick == 2;
                if (claim) {
                    assertTrue(vy > 0 && at.y > FLOOR_Y + 1, "the claim is made on a rising tick past the ledge top");
                    at = new Vec3(at.x, FLOOR_Y + 1, 0.5);
                }
                result = f.tick(at, claim);
                if (!claim) assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                vx = dx * (ground ? STONE_FRICTION : 0.91F);
                vy = (vy - 0.08) * 0.98F;
            }
            assertRejected(result, "GroundSpoof");
            Vec3 from = at;
            double dx = vx + ClientMoveInput.of(f, FORWARD_JUMP, 1.0F, 0.1F, EAST).x;
            PredictionResult rejump = f.tick(from.add(dx, 0.42F, 0.0), false);
            assertFalse(exact(rejump), describe(rejump));
        }
    }

    @Test
    void walkingUpASlabClaimsTheGround() throws Exception {
        Vec3 start = new Vec3(0.65, FLOOR_Y, 0.5);
        try (var f = ledge(STONE_SLAB, start)) {
            f.input(NONE);
            f.tickEnd();
            f.input(FORWARD);
            double dx = ClientMoveInput.of(f, FORWARD, 1.0F, GROUND_SPEED, EAST).x;
            assertTrue(start.x + 0.3 + dx > 1.0, "the walk reaches the slab");
            PredictionResult result = f.tick(new Vec3(start.x + dx, FLOOR_Y + 0.5, 0.5), true);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void landingFromAJumpClaimsTheGround() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
            f.input(NONE);
            f.tickEnd();
            f.input(JUMP);
            double y = FLOOR_Y;
            double vy = 0.42F;
            for (int tick = 0; tick < 20; tick++) {
                boolean lands = y + vy <= FLOOR_Y;
                y = lands ? FLOOR_Y : y + vy;
                PredictionResult result = f.tick(new Vec3(0.5, y, 0.5), lands);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                if (lands) return;
                if (tick == 0) f.input(NONE);
                vy = (vy - 0.08) * 0.98F;
            }
            throw new AssertionError("no landing");
        }
    }

    /** The falling tick that ends on a ledge top over only the edge of the box collides below and grounds it. */
    @Test
    void aJumpLandingWithOnlyTheBoxEdgeOverALedgeClaimsTheGround() throws Exception {
        Vec3 start = new Vec3(0.75, FLOOR_Y + 1, 0.5);
        try (var f = ledge(BlockIds.STONE.defaultState(), start)) {
            f.player.onGround = f.player.lastOnGround = true;
            f.player.checkManager.getSimulationProcessor().setLastOnGround(DesyncStatus.TRUE);
            f.input(NONE);
            f.tickEnd();
            f.input(JUMP);
            double y = start.y;
            double vy = 0.42F;
            for (int tick = 0; tick < 20; tick++) {
                boolean lands = y + vy <= start.y;
                y = lands ? start.y : y + vy;
                PredictionResult result = f.tick(new Vec3(start.x, y, start.z), lands);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                if (lands) return;
                if (tick == 0) f.input(NONE);
                vy = (vy - 0.08) * 0.98F;
            }
            throw new AssertionError("no landing");
        }
    }

    /**
     * A forward jump from against a one-block ledge: Entity#collide moves Y before X, so X is stopped by the ledge's
     * side until the box has risen past its top; the first falling tick that ends over the top lands on it.
     */
    @Test
    void aJumpOntoALedgeLandsOnItsTop() throws Exception {
        double halfWidth = 0.6F / 2.0;
        Vec3 start = new Vec3(0.69, FLOOR_Y, 0.5);
        try (var f = ledge(BlockIds.STONE.defaultState(), start)) {
            f.input(NONE);
            f.tickEnd();
            f.input(FORWARD_JUMP);
            double top = FLOOR_Y + 1;
            double x = start.x;
            double y = start.y;
            double vx = 0.0;
            double vy = 0.42F;
            for (int tick = 0; tick < 20; tick++) {
                boolean ground = tick == 0;
                ServerboundPlayerInput input = ground ? FORWARD_JUMP : FORWARD;
                double dx = vx + ClientMoveInput.of(f, input, 1.0F, ground ? 0.1F : 0.02F, EAST).x;
                boolean overTop = x + halfWidth > 1.0;
                boolean lands = vy < 0 && overTop && y + vy <= top;
                y = lands ? top : y + vy;
                boolean blocked = y < top && x + dx + halfWidth > 1.0;
                x = blocked ? 1.0 - halfWidth : x + dx;
                PredictionResult result = f.tick(new Vec3(x, y, start.z), lands);
                assertTrue(exact(result), "tick " + tick + ": " + describe(result));
                if (lands) return;
                if (ground) f.input(FORWARD);
                vx = blocked ? 0.0 : dx * (ground ? STONE_FRICTION : 0.91F);
                vy = (vy - 0.08) * 0.98F;
            }
            throw new AssertionError("no landing");
        }
    }

    @Test
    void aJumpStoppedByTheCeilingStaysAirborne() throws Exception {
        try (var f = underCeiling()) {
            PredictionResult result = f.tick(new Vec3(0.5, FLOOR_Y + 0.2, 0.5), false);
            assertTrue(exact(result), describe(result));
        }
    }

    @Test
    void aGroundClaimOnAJumpStoppedByTheCeilingFlags() throws Exception {
        try (var f = underCeiling()) {
            assertRejected(f.tick(new Vec3(0.5, FLOOR_Y + 0.2, 0.5), true), "GroundSpoof");
        }
    }

    private static JavaTickFixture ledge(int block, Vec3 start) throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(1, FLOOR_Y, 0), block);
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.player.xRot = EAST;
        f.player.yRot = 0.0F;
        f.place(start, Vec3.ZERO);
        return f;
    }

    /** A block whose underside is 2.0 above the floor stops the 0.42 jump of a 1.8 tall player after 0.2. */
    private static JavaTickFixture underCeiling() throws Exception {
        JavaTickFixture f = new JavaTickFixture();
        f.player.compensatedWorld.updateBlock(new BlockPos(0, FLOOR_Y + 2, 0), BlockIds.STONE.defaultState());
        for (int tick = 0; tick < 3; tick++) f.player.compensatedWorld.removeInvalidPistonLikeStuff(0);
        f.place(new Vec3(0.5, FLOOR_Y, 0.5), Vec3.ZERO);
        f.input(NONE);
        f.tickEnd();
        f.input(JUMP);
        return f;
    }
}
