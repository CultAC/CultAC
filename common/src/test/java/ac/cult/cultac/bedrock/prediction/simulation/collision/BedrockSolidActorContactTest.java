package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A solid actor is a shape of the move like a block, met before the blocks, and every move that has one may leave a
 * shape its box overlaps by up to the actor's limit through the nearest face: 0.1 on each axis for most actors.
 * Nothing pushes the player: standing among boats and shulkers, at the translator's world border and landing on a
 * happy ghast, the client reports exactly these moves.
 */
public final class BedrockSolidActorContactTest {
    private static final Vec3d GRAVITY = new Vec3d(0.0, -0.0784, 0.0);

    /** Seven boats crowd an idle player: it leaves the nearest face of each in turn, last collected first. */
    @Test
    public void anIdlePlayerLeavesTheBoatsThatOverlapIt() {
        var moved = move(
                new Vec3d(41.5, 120.0, 25.5),
                GRAVITY,
                boat(42.537140, 25.509272),
                boat(42.222977, 26.288782),
                boat(41.416580, 26.568342),
                boat(40.670177, 26.217831),
                boat(40.396160, 25.407238),
                boat(40.758640, 24.641069),
                boat(42.335320, 24.707376));

        assertEquals(-0.062859F, (float) moved.appliedDelta().x(), 2.0E-6F);
        assertEquals(0.0F, (float) moved.appliedDelta().y(), 0);
        assertEquals(-0.031656F, (float) moved.appliedDelta().z(), 2.0E-6F);
        assertTrue(moved.xCollision());
        assertTrue(moved.yCollision());
        assertTrue(moved.zCollision());
    }

    @Test
    public void fourBoatsAtTheCornersMoveItOutOfTheLastCollectedOnEachAxis() {
        var moved = move(
                new Vec3d(41.49616, 120.0, 25.468344),
                GRAVITY,
                boat(42.402203, 26.511587),
                boat(40.458900, 26.379202),
                boat(40.612503, 24.436729),
                boat(42.527992, 24.540745));

        assertEquals(0.062740F, (float) moved.appliedDelta().x(), 2.0E-6F);
        assertEquals(-0.056755F, (float) moved.appliedDelta().z(), 2.0E-6F);
    }

    /**
     * The border's two actors still lerp towards the border: one overlaps the player by 0.022 and is left, the
     * other and a boat hold it too deep and are no part of the move.
     */
    @Test
    public void aBorderActorThatOverlapsByLessThanTheLimitIsLeftAndADeeperOneIgnored() {
        var moved = move(
                new Vec3d(-5.5, 120.0, 50.5),
                GRAVITY,
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -10.5F, 110.0F, 40.222225F, -0.5F, 135.0F, 50.222225F),
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -5.777778F, 110.0F, 45.5F, 4.222222F, 135.0F, 55.5F),
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -6.3F, 120.0F, 49.7F, -4.7F, 120.6F, 51.3F));

        assertEquals(0.0F, (float) moved.appliedDelta().x(), 0);
        assertEquals(0.022224F, (float) moved.appliedDelta().z(), 2.0E-6F);
        assertFalse(moved.xCollision());
        assertTrue(moved.zCollision());
    }

    /**
     * Refreshing the nearby actors for replay preserves their depenetration limits; it does not turn their overlap
     * into free movement through the shapes.
     */
    @Test
    public void aReplayedTickKeepsTheBoatsDepenetrationLimits() {
        var moved = move(
                new Vec3d(41.49616, 120.0, 25.468344),
                GRAVITY,
                BedrockDepenetrationLimit.replayed(boat(42.402203, 26.511587)),
                BedrockDepenetrationLimit.replayed(boat(40.458900, 26.379202)),
                BedrockDepenetrationLimit.replayed(boat(40.612503, 24.436729)),
                BedrockDepenetrationLimit.replayed(boat(42.527992, 24.540745)));

        assertEquals(0.062740F, (float) moved.appliedDelta().x(), 2.0E-6F);
        assertEquals(-0.056755F, (float) moved.appliedDelta().z(), 2.0E-6F);
    }

    /**
     * A replayed tick still stops at a solid actor it does not overlap: a capture shows the player corrected against
     * the translator's world border with 0.117859 a tick towards it, and the replay ends at its face.
     */
    @Test
    public void aReplayedTickStopsAtTheFaceOfASolidActor() {
        var border = actor(BedrockDepenetrationLimit.SOLID_ACTOR, -34.5F, 110.0F, -12.0F, -24.5F, 135.0F, -2.0F);
        var moved = move(
                new Vec3d(-29.506498F, 120.0, -1.6999993F),
                new Vec3d(0.0, -0.0784, -0.117859F),
                BedrockDepenetrationLimit.replayed(border));

        assertEquals(0.0F, (float) moved.appliedDelta().z(), 1.0E-6F);
        assertTrue(moved.zCollision());
    }

    @Test
    public void anOverlapDeeperThanTheLimitOnItsSmallestAxisLeavesThePlayerInPlace() {
        // The boat's face is 0.193624 inside the player's box along x, further on the other axes.
        var moved = move(new Vec3d(41.5, 120.0, 25.5), GRAVITY, boat(41.8F - 0.193624F + 0.8F, 25.5));

        assertEquals(Vec3d.ZERO, moved.appliedDelta());
        assertFalse(moved.xCollision());
        assertTrue(moved.yCollision());
        assertFalse(moved.zCollision());
    }

    /** A solid actor in reach raises the limit of the whole move, so a block overlapped by 0.05 is left too. */
    @Test
    public void aCollectedActorLetsTheMoveLeaveABlockAsWell() {
        var feet = new Vec3d(41.5, 120.0, 25.5);
        var block = stone(42, 120, 25, 41.75F);
        var far = boat(44.0, 25.5);

        assertEquals(0.0F, (float) move(feet, GRAVITY, block).appliedDelta().x(), 0);
        assertEquals(
                -0.05F, (float) move(feet, GRAVITY, block, far).appliedDelta().x(), 2.0E-6F);
    }

    /** Feet up to two blocks inside a happy ghast's top are lifted onto it in one tick. */
    @Test
    public void aHappyGhastLiftsThePlayerStandingInsideItsTop() {
        var ghast = ghast(126.331795F);
        var moved = moveInAir(new Vec3d(115.9, 126.0811F, 50.0), Vec3d.ZERO, ghast);

        assertEquals(0.250694F, (float) moved.appliedDelta().y(), 2.0E-6F);
        assertTrue(moved.yCollision());

        // Any other solid actor that deep is no part of the move.
        var other = actor(
                BedrockDepenetrationLimit.SOLID_ACTOR, 113.931F, 122.327795F, 48.0F, 117.935F, 126.331795F, 52.004F);
        assertEquals(
                0.0F,
                (float) moveInAir(new Vec3d(115.9, 126.0811F, 50.0), Vec3d.ZERO, other)
                        .appliedDelta()
                        .y(),
                0);
    }

    @Test
    public void aFallOntoAHappyGhastStopsOnItsTop() {
        var landed = moveInAir(new Vec3d(115.9, 126.253395F, 50.0), new Vec3d(0, -0.155232, 0), ghast(126.194740F));
        assertEquals(-0.058655F, (float) landed.appliedDelta().y(), 2.0E-6F);
        assertTrue(landed.yCollision());

        var falling = moveInAir(new Vec3d(115.9, 126.331795F, 50.0), GRAVITY, ghast(126.249550F));
        assertEquals(-0.0784F, (float) falling.appliedDelta().y(), 0);
        assertFalse(falling.yCollision());

        var reached = moveInAir(new Vec3d(115.9, 126.158195F, 50.0), GRAVITY, ghast(126.133827F));
        assertEquals(-0.024368F, (float) reached.appliedDelta().y(), 2.0E-6F);
        assertTrue(reached.yCollision());
    }

    /**
     * A shulker gives the move a limit along the axis its lid opens on only: closed, or open on the floor, it leaves
     * the player it overlaps by 0.05 sideways where it is.
     */
    @Test
    public void aShulkerIsLeftOnlyAlongTheAxisItsLidOpensOn() {
        var feet = new Vec3d(113.75, 120.0, -26.5);
        var closed = new BedrockDepenetrationLimit(0.0F, 0.0F, 0.0F);
        var moved = move(feet, GRAVITY, actor(closed, 114.0001F, 120.0F, -26.9999F, 114.9999F, 120.98F, -26.0001F));
        assertEquals(0.0F, (float) moved.appliedDelta().x(), 0);
        assertFalse(moved.xCollision());

        var open = new BedrockDepenetrationLimit(0.0F, 1.1F, 0.0F);
        moved = move(feet, GRAVITY, actor(open, 114.0001F, 120.0F, -26.9999F, 114.9999F, 121.98F, -26.0001F));
        assertEquals(0.0F, (float) moved.appliedDelta().x(), 0);
    }

    /**
     * A shulker on a wall whose lid opens into the player standing 0.3 inside its last step: each tick the player is
     * moved out to the lid's face, which a limit of 1.1 times the lid allows.
     */
    @Test
    public void aShulkerOpeningIntoThePlayerMovesItOutToTheLidEachTick() {
        float[] lid = {0.9454859F, 0.97552526F, 0.9938397F, 1.0F};
        double[] feetZ = {-13.0001, -12.754614, -12.724575, -12.706261};
        float[] steps = {0.245486F, 0.030039F, 0.018314F, 0.006161F};
        for (int i = 0; i < lid.length; i++) {
            var limit = new BedrockDepenetrationLimit(0.0F, 0.0F, 1.1F * lid[i]);
            float face = -14.0F + lid[i] - 1.0E-4F;
            var shulker = actor(limit, 109.0001F, 120.0F, -14.9999F, 109.9999F, 120.98F, face);
            var moved = move(new Vec3d(109.5, 120.0, feetZ[i]), GRAVITY, shulker);
            assertEquals("tick " + i, steps[i], (float) moved.appliedDelta().z(), 2.0E-6F);
            assertTrue(moved.zCollision());
        }
    }

    /** A move over a floor at the feet, with {@code shapes} after it in collection order. */
    private static BedrockCollisionSweep.MoveResult move(Vec3d feet, Vec3d requested, PlacedBlockCollision... shapes) {
        var blocks = new ArrayList<PlacedBlockCollision>();
        int floor = (int) Math.floor(feet.y()) - 1;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                blocks.add(stone((int) Math.floor(feet.x()) + x, floor, (int) Math.floor(feet.z()) + z, Float.NaN));
            }
        }
        blocks.addAll(List.of(shapes));
        return sweep(feet, requested, blocks);
    }

    private static BedrockCollisionSweep.MoveResult moveInAir(
            Vec3d feet, Vec3d requested, PlacedBlockCollision... shapes) {
        return sweep(feet, requested, List.of(shapes));
    }

    private static BedrockCollisionSweep.MoveResult sweep(
            Vec3d feet, Vec3d requested, List<PlacedBlockCollision> shapes) {
        var standing = BedrockMovementState.fromPhysicalFeet(
                feet, requested, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
        return BedrockEntityMove.actorMove(
                        standing, requested, new BlockCollisionWorld(shapes), PlayerDimensionsState.DEFAULT)
                .baseMove();
    }

    /** A boat 1.6 wide and 0.6 tall on the floor at y 120. */
    private static PlacedBlockCollision boat(double x, double z) {
        float fx = (float) x;
        float fz = (float) z;
        return actor(BedrockDepenetrationLimit.SOLID_ACTOR, fx - 0.8F, 120.0F, fz - 0.8F, fx + 0.8F, 120.6F, fz + 0.8F);
    }

    /** A happy ghast 4.004 wide and tall whose top is at {@code top}. */
    private static PlacedBlockCollision ghast(float top) {
        return actor(BedrockDepenetrationLimit.HAPPY_GHAST, 113.931F, top - 4.004F, 48.0F, 117.935F, top, 52.004F);
    }

    private static PlacedBlockCollision actor(
            BedrockDepenetrationLimit limit, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return limit.solidActor(new WorldCollisionBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** A stone block whose west face is moved to {@code minX} unless that is NaN. */
    private static PlacedBlockCollision stone(int x, int y, int z, float minX) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(Float.isNaN(minX) ? x : minX, y, z, x + 1, y + 1, z + 1)));
    }
}
