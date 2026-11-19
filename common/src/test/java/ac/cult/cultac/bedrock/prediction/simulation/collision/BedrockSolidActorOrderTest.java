package ac.cult.cultac.bedrock.prediction.simulation.collision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockForwardTick;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The client meets the solid actors of a move in an order Cult cannot know. A move that leaves two actors in opposite
 * directions along one axis ends where the one met last leaves it, so the tick takes a move for each side winning
 * along each such axis; any other tick has its one move. The crowds are the boats an idle player stood among.
 */
public final class BedrockSolidActorOrderTest {
    private static final Vec3d GRAVITY = new Vec3d(0.0, -0.0784, 0.0);
    private static final float TOLERANCE = 2.0E-6F;

    /** Seven boats crowd the player; two of them leave it along x either way round. */
    @Test
    public void sevenBoatsEndTheMoveWhereEitherOfTwoLeavesItAlongX() {
        var ends = sevenBoats();

        assertEnds(ends, new double[][] {{-0.062859, 0, -0.031656}, {-0.003841, 0, -0.031656}});
        for (var end : ends.results()) {
            assertTrue(end.xCollision());
            assertTrue(end.zCollision());
            assertTrue(end.verticalCollision());
            // A collided axis keeps no velocity, whichever end the move took.
            assertEquals(0.0D, end.velocity().x(), 0.0D);
            assertEquals(0.0D, end.velocity().z(), 0.0D);
        }
    }

    /** Four boats at the corners: two leave the player along each axis, either way round on each. */
    @Test
    public void fourBoatsAtTheCornersEndTheMoveWhereEitherOfEachPairLeavesIt() {
        var ends = moves(
                new Vec3d(41.49616, 120.0, 25.468344),
                boat(42.402203, 26.511587),
                boat(40.458900, 26.379202),
                boat(40.612503, 24.436729),
                boat(42.527992, 24.540745));

        assertEnds(ends, new double[][] {
            {0.062740, 0, -0.056755}, {-0.068165, 0, -0.056755}, {0.062740, 0, 0.068384}, {-0.068165, 0, 0.068384}
        });
    }

    /** The border's near actor and a boat too deep to leave: the order of the three cannot change the end. */
    @Test
    public void actorsThatCannotDisagreeGiveOneEnd() {
        var ends = moves(
                new Vec3d(-5.5, 120.0, 50.5),
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -10.5F, 110.0F, 40.222225F, -0.5F, 135.0F, 50.222225F),
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -5.777778F, 110.0F, 45.5F, 4.222222F, 135.0F, 55.5F),
                actor(BedrockDepenetrationLimit.SOLID_ACTOR, -6.3F, 120.0F, 49.7F, -4.7F, 120.6F, 51.3F));

        assertEnds(ends, new double[][] {{0, 0, 0.022224}});
    }

    @Test
    public void aBoatOverlappedDeeperThanTheLimitGivesOneEndInPlace() {
        var ends = moves(
                new Vec3d(41.5, 120.0, 25.5), boat(41.8F - 0.193624F + 0.8F, 25.5), boat(41.5, 25.5 - 0.3 - 0.8 - 0.5));

        assertEnds(ends, new double[][] {{0, 0, 0}});
        assertFalse(ends.results().getFirst().xCollision());
    }

    /** One boat overlapping the player and others nearby that it is not moved into: one end. */
    @Test
    public void aSingleOverlappedBoatGivesOneEnd() {
        var ends = moves(
                new Vec3d(41.5, 120.0, 25.5),
                boat(41.8F - 0.05F + 0.8F, 25.5),
                boat(41.5, 25.5 + 0.3 + 0.8 + 0.5),
                boat(41.5, 25.5 - 0.3 - 0.8 - 0.5));

        assertEnds(ends, new double[][] {{-0.05, 0, 0}});
    }

    /** No order of the seven boats ends the move between the two ends, so a claim there is met by no candidate. */
    @Test
    public void noOrderReachesAPositionBetweenTheEnds() {
        var ends = sevenBoats();
        var claimed = ends.feet().add(new Vec3d(-0.033350, 0.0, -0.031656));
        for (var end : ends.results()) {
            assertTrue(end.position().subtract(claimed).length() > 0.02D);
        }
    }

    /** Each of four boats twice over: the same ends, each twin kept beside the other. */
    @Test
    public void twinBoatsFindTheSameEnds() {
        var boats = new ArrayList<PlacedBlockCollision>();
        for (int copy = 0; copy < 2; copy++) {
            boats.add(boat(42.402203, 26.511587));
            boats.add(boat(40.458900, 26.379202));
            boats.add(boat(40.612503, 24.436729));
            boats.add(boat(42.527992, 24.540745));
        }
        var ends = moves(new Vec3d(41.49616, 120.0, 25.468344), boats.toArray(PlacedBlockCollision[]::new));

        assertEnds(ends, new double[][] {
            {0.062740, 0, -0.056755}, {-0.068165, 0, -0.056755}, {0.062740, 0, 0.068384}, {-0.068165, 0, 0.068384}
        });
    }

    /** Two crossings with one boat reaching across from each side: exact ends, no range. */
    @Test
    public void oneBoatFromEachSideGivesExactEndsWithoutRange() {
        for (var end : sevenBoats().results()) assertNull(end.solidActorOrderRange());
        var corners = moves(
                new Vec3d(41.49616, 120.0, 25.468344),
                boat(42.402203, 26.511587),
                boat(40.458900, 26.379202),
                boat(40.612503, 24.436729),
                boat(42.527992, 24.540745));
        for (var end : corners.results()) assertNull(end.solidActorOrderRange());
    }

    /**
     * Two boats overlap the player from the west by 0.06 and 0.03 and one from the east by 0.05: the order that meets
     * the 0.03 boat last ends between the other two, so the move carries x from -0.05 to 0.06 as a range.
     */
    @Test
    public void threeBoatsAcrossOneAxisGiveARangeBetweenItsEnds() {
        var ends = threeBoats();

        assertEquals(1, ends.results().size());
        var end = ends.results().getFirst();
        Vec3d moved = end.position().subtract(ends.feet());
        var range = end.solidActorOrderRange();
        assertNotNull(range);
        assertEquals(0.06, moved.x(), TOLERANCE);
        assertEquals(-0.05, moved.x() + range.minX(), TOLERANCE);
        assertEquals(0.06, moved.x() + range.maxX(), TOLERANCE);
        assertEquals(0.0, range.minZ(), 0.0);
        assertEquals(0.0, range.maxZ(), 0.0);
        assertEquals("x:0.110", range.describe());
    }

    /** A client end strictly between the range's ends is met exactly; one just outside is met only at the end. */
    @Test
    public void aClaimInsideTheRangeIsMetAndOneOutsideIsNot() {
        var ends = threeBoats();
        var end = ends.results().getFirst();
        Vec3d moved = end.position().subtract(ends.feet());
        var range = end.solidActorOrderRange();

        var inside = new Vec3d(0.03, moved.y(), moved.z());
        assertEquals(0.0D, range.closest(moved, inside).subtract(inside).length(), 0.0D);

        var beyond = new Vec3d(moved.x() + range.maxX() + 0.001, moved.y(), moved.z());
        assertEquals(0.001D, range.closest(moved, beyond).subtract(beyond).length(), 1.0E-9D);
        var before = new Vec3d(moved.x() + range.minX() - 0.001, moved.y(), moved.z());
        assertEquals(0.001D, range.closest(moved, before).subtract(before).length(), 1.0E-9D);
        // Another axis keeps the move's own end.
        var aside = new Vec3d(0.03, moved.y(), moved.z() + 0.001);
        assertEquals(0.001D, range.closest(moved, aside).subtract(aside).length(), 1.0E-9D);
    }

    private static Ends threeBoats() {
        return moves(
                new Vec3d(41.5, 120.0, 25.5),
                boat(41.2F + 0.06F - 0.8F, 25.5),
                boat(41.2F + 0.03F - 0.8F, 25.5),
                boat(41.8F - 0.05F + 0.8F, 25.5));
    }

    /** Three axes crossed exactly at once still give at most four moves: X and Z decide, Y is left to a range. */
    @Test
    public void aTickTakesAtMostFourMoves() {
        int axes = BedrockSolidActorOrders.decidedAxes(7);
        assertEquals(5, axes);
        assertEquals(BedrockSolidActorOrders.MAX_ORDERS, BedrockSolidActorOrders.count(axes));
        assertEquals(2, BedrockSolidActorOrders.count(BedrockSolidActorOrders.decidedAxes(2)));
    }

    /** The tick's candidates are its moves, each with its own state: the seven boats give both x ends. */
    @Test
    public void eachMoveIsACandidateOfTheTick() {
        var feet = new Vec3d(41.5, 120.0, 25.5);
        var world = new ArrayList<PlacedBlockCollision>(floor(feet));
        world.add(boat(42.537140, 25.509272));
        world.add(boat(42.222977, 26.288782));
        world.add(boat(41.416580, 26.568342));
        world.add(boat(40.670177, 26.217831));
        world.add(boat(40.396160, 25.407238));
        world.add(boat(40.758640, 24.641069));
        world.add(boat(42.335320, 24.707376));
        var context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(world)),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var previous = BedrockMovementState.fromPhysicalFeet(
                feet, GRAVITY, BedrockInputFrame.idle(1), BedrockCollisionFlags.ON_GROUND);
        var input = BedrockInputFrame.idle(2);
        var candidates = BedrockForwardTick.simulate(new BedrockSimulation.Input(
                previous,
                input,
                input.intent(),
                BedrockWorldSnapshot.fromContext(context),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                BedrockMobJumpComponentState.DEFAULT,
                true,
                false,
                Vec3d.ZERO));

        assertTrue(candidates.size() >= 2 && candidates.size() <= BedrockSolidActorOrders.MAX_ORDERS);
        boolean near = false;
        boolean far = false;
        for (var candidate : candidates) {
            var result = candidate.movementResult();
            Vec3d moved = result.predictedPosition().subtract(feet);
            near |= Math.abs(moved.x() + 0.003841) <= TOLERANCE;
            far |= Math.abs(moved.x() + 0.062859) <= TOLERANCE;
            assertEquals(-0.031656, moved.z(), TOLERANCE);
            assertEquals(candidates.size(), result.solidActorOrders());
            assertEquals(0.0D, result.predictedVelocity().x(), 0.0D);
        }
        assertTrue(near && far);
    }

    private static Ends sevenBoats() {
        return moves(
                new Vec3d(41.5, 120.0, 25.5),
                boat(42.537140, 25.509272),
                boat(42.222977, 26.288782),
                boat(41.416580, 26.568342),
                boat(40.670177, 26.217831),
                boat(40.396160, 25.407238),
                boat(40.758640, 24.641069),
                boat(42.335320, 24.707376));
    }

    /** The tick's moves end exactly at {@code expected}, as moves from the feet, each reached by some move. */
    private static void assertEnds(Ends ends, double[][] expected) {
        assertTrue(describe(ends), ends.results().size() <= BedrockSolidActorOrders.MAX_ORDERS);
        for (double[] end : expected) {
            boolean found = false;
            for (var result : ends.results()) found |= matches(ends.feet(), result, end);
            assertTrue(describe(ends), found);
        }
        for (var result : ends.results()) {
            boolean expectedEnd = false;
            for (double[] end : expected) expectedEnd |= matches(ends.feet(), result, end);
            assertTrue(describe(ends), expectedEnd);
        }
        assertEquals(describe(ends), expected.length == 1, ends.results().size() == 1);
    }

    private static boolean matches(Vec3d feet, BedrockEntityMove.Result end, double[] expected) {
        Vec3d delta = end.position().subtract(feet);
        return Math.abs(delta.x() - expected[0]) <= TOLERANCE
                && Math.abs(delta.y() - expected[1]) <= TOLERANCE
                && Math.abs(delta.z() - expected[2]) <= TOLERANCE;
    }

    private static String describe(Ends ends) {
        return ends.results().stream()
                .map(end -> end.position().subtract(ends.feet()).toString())
                .toList()
                .toString();
    }

    private static List<PlacedBlockCollision> floor(Vec3d feet) {
        var blocks = new ArrayList<PlacedBlockCollision>();
        int floor = (int) Math.floor(feet.y()) - 1;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                blocks.add(stone((int) Math.floor(feet.x()) + x, floor, (int) Math.floor(feet.z()) + z));
            }
        }
        return blocks;
    }

    private record Ends(Vec3d feet, List<BedrockEntityMove.Result> results) {}

    /** An idle move over a floor at the feet, with {@code shapes} after it in collection order. */
    private static Ends moves(Vec3d feet, PlacedBlockCollision... shapes) {
        var blocks = new ArrayList<PlacedBlockCollision>(floor(feet));
        blocks.addAll(List.of(shapes));
        var standing = BedrockMovementState.fromPhysicalFeet(
                feet, GRAVITY, BedrockInputFrame.idle(0), BedrockCollisionFlags.ON_GROUND);
        var dimensions = PlayerDimensionsState.DEFAULT;
        var fetchBox = BedrockCollisionFetchBox.of(
                standing.collisionBox(dimensions),
                GRAVITY,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                standing.coordinateFrame());
        var ends = new ArrayList<BedrockEntityMove.Result>();
        var first = move(standing, blocks, dimensions, feet, fetchBox, 0);
        ends.add(first);
        int collected = first.solidActorOrder();
        for (int index = 1;
                collected != 0 && index < BedrockSolidActorOrders.count(BedrockSolidActorOrders.axes(collected));
                index++) {
            ends.add(move(
                    standing, blocks, dimensions, feet, fetchBox, BedrockSolidActorOrders.order(collected, index)));
        }
        return new Ends(feet, ends);
    }

    private static BedrockEntityMove.Result move(
            BedrockMovementState standing,
            List<PlacedBlockCollision> blocks,
            PlayerDimensionsState dimensions,
            Vec3d feet,
            WorldCollisionBox fetchBox,
            int order) {
        return BedrockEntityMove.move(
                standing,
                BedrockInputFrame.idle(1),
                new BlockCollisionWorld(blocks),
                dimensions,
                feet.add(GRAVITY),
                GRAVITY,
                GRAVITY.y(),
                true,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                false,
                fetchBox,
                order);
    }

    /** A boat 1.6 wide and 0.6 tall on the floor at y 120. */
    private static PlacedBlockCollision boat(double x, double z) {
        float fx = (float) x;
        float fz = (float) z;
        return actor(BedrockDepenetrationLimit.SOLID_ACTOR, fx - 0.8F, 120.0F, fz - 0.8F, fx + 0.8F, 120.6F, fz + 0.8F);
    }

    private static PlacedBlockCollision actor(
            BedrockDepenetrationLimit limit, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return limit.solidActor(new WorldCollisionBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static PlacedBlockCollision stone(int x, int y, int z) {
        return PlacedBlockCollision.manual(
                new BlockPosition(x, y, z),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(x, y, z, x + 1, y + 1, z + 1)));
    }
}
