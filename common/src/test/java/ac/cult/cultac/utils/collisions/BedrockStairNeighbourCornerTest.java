package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A client whose stairs carry no corner state derives a stair's corner from the stairs around it, whatever corner the
 * Java state names. A stone brick stair placed alone as an east-facing inner_left corner let a Bedrock player walk,
 * feet 0.676 above its floor, to 0.0717 past its west face, where the Java corner raises the north-west quarter.
 */
class BedrockStairNeighbourCornerTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPosition STAIR = new BlockPosition(20131, 120, 20324);

    @Test
    void aLoneStairNamedAsAnInnerCornerIsStraight() {
        int state = stair("minecraft:stone_brick_stairs", "east", "bottom", "inner_left");
        // The captured client feet after the move that crossed the west face.
        WorldCollisionBox player = new WorldCollisionBox(
                20130.7717 - 0.3, 120.67646, 20324.5 - 0.3, 20130.7717 + 0.3, 120.67646 + 1.8, 20324.5 + 0.3);
        assertFalse(intersects(boxes(Map.of(STAIR, state), STAIR), player), "derived from neighbours");
        assertTrue(intersects(ownCornerBoxes(state), player), "the corner the state carries");
        assertEquals(Set.of("NE", "SE"), raised(Map.of(STAIR, state), STAIR, "bottom"));
    }

    @Test
    void theStairBehindTurnsItIntoAnOuterCornerFirst() {
        // facing, the stair behind's facing, the quarter left raised
        assertOuter("east", "north", "NE");
        assertOuter("east", "south", "SE");
        assertOuter("west", "north", "NW");
        assertOuter("west", "south", "SW");
        assertOuter("south", "west", "SW");
        assertOuter("south", "east", "SE");
        assertOuter("north", "west", "NW");
        assertOuter("north", "east", "NE");
    }

    @Test
    void theStairInFrontAddsAnInnerQuarter() {
        // facing, the stair in front's facing, the quarter added to the facing half
        assertInner("east", "north", "NW");
        assertInner("east", "south", "SW");
        assertInner("west", "north", "NE");
        assertInner("west", "south", "SE");
        assertInner("south", "west", "NW");
        assertInner("south", "east", "NE");
        assertInner("north", "west", "SW");
        assertInner("north", "east", "SE");
    }

    @Test
    void onlyTheSameBlockOnTheSideKeepsTheStairStraight() {
        int self = stair("minecraft:oak_stairs", "east", "bottom", "straight");
        int behind = stair("minecraft:stone_stairs", "north", "bottom", "straight");
        // Side for an outer corner turned north is the block south of the stair.
        BlockPosition side = offset(STAIR, 0, 1);
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(STAIR, self, offset(STAIR, 1, 0), behind));
        assertEquals(Set.of("NE"), raised(world, STAIR, "bottom"), "any stair behind turns it");
        world.put(side, stair("minecraft:stone_stairs", "east", "bottom", "straight"));
        assertEquals(Set.of("NE"), raised(world, STAIR, "bottom"), "another block on the side does not block it");
        world.put(side, stair("minecraft:oak_stairs", "east", "bottom", "outer_left"));
        assertEquals(Set.of("NE", "SE"), raised(world, STAIR, "bottom"), "the same stair on the side blocks it");
    }

    @Test
    void aStairOfTheOtherHalfOrAlongTheSameAxisLeavesItStraight() {
        int self = stair("minecraft:oak_stairs", "east", "bottom", "straight");
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(STAIR, self));
        world.put(offset(STAIR, 1, 0), stair("minecraft:oak_stairs", "north", "top", "straight"));
        world.put(offset(STAIR, -1, 0), stair("minecraft:oak_stairs", "west", "bottom", "straight"));
        assertEquals(Set.of("NE", "SE"), raised(world, STAIR, "bottom"));
        int top = stair("minecraft:oak_stairs", "east", "top", "straight");
        world.put(STAIR, top);
        world.put(offset(STAIR, 1, 0), stair("minecraft:oak_stairs", "north", "top", "straight"));
        assertEquals(Set.of("NE"), raised(world, STAIR, "top"), "a top stair turns with a top stair");
    }

    private static void assertOuter(String facing, String behindFacing, String quarter) {
        int[] step = step(facing);
        Map<BlockPosition, Integer> world = Map.of(
                STAIR,
                stair("minecraft:oak_stairs", facing, "bottom", "straight"),
                offset(STAIR, step[0], step[1]),
                stair("minecraft:oak_stairs", behindFacing, "bottom", "straight"));
        assertEquals(Set.of(quarter), raised(world, STAIR, "bottom"), facing + " behind " + behindFacing);
    }

    private static void assertInner(String facing, String frontFacing, String quarter) {
        int[] step = step(facing);
        Map<BlockPosition, Integer> world = Map.of(
                STAIR,
                stair("minecraft:oak_stairs", facing, "bottom", "straight"),
                offset(STAIR, -step[0], -step[1]),
                stair("minecraft:oak_stairs", frontFacing, "bottom", "straight"));
        Set<String> expected = new java.util.HashSet<>(raised(Map.of(STAIR, world.get(STAIR)), STAIR, "bottom"));
        expected.add(quarter);
        assertEquals(expected, raised(world, STAIR, "bottom"), facing + " front " + frontFacing);
    }

    private static int[] step(String facing) {
        return switch (facing) {
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            case "south" -> new int[] {0, 1};
            default -> new int[] {0, -1};
        };
    }

    private static Set<String> raised(Map<BlockPosition, Integer> world, BlockPosition position, String half) {
        List<WorldCollisionBox> boxes = boxes(world, position);
        double stepY = half.equals("bottom") ? 0.75 : 0.25;
        Set<String> raised = new java.util.TreeSet<>();
        for (String quarter : List.of("NW", "NE", "SW", "SE")) {
            double x = position.x() + (quarter.endsWith("W") ? 0.25 : 0.75);
            double z = position.z() + (quarter.startsWith("N") ? 0.25 : 0.75);
            if (inside(boxes, x, position.y() + stepY, z)) raised.add(quarter);
        }
        return raised;
    }

    private static List<WorldCollisionBox> boxes(Map<BlockPosition, Integer> world, BlockPosition position) {
        var context = new BedrockBlockShapeContext(
                BedrockBlockShapeContext.NONE.shulkerBoxLift(),
                BedrockBlockShapeContext.NONE.movingBlockBoxes(),
                BedrockBlockShapeContext.NONE.pistonProgress(),
                BedrockCoordinateFrame.IDENTITY,
                BedrockBlockShapeContext.NONE.movingBlockCarried(),
                new BedrockBlockShapeContext.DerivedShapes(
                        at -> world.getOrDefault(
                                at, REGISTRY.block("minecraft:air").defaultState()),
                        true));
        return new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.nativeFallback(), context)
                .block(position, world.get(position), BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
    }

    private static List<WorldCollisionBox> ownCornerBoxes(int state) {
        return new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.nativeFallback())
                .block(STAIR, state, BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
    }

    private static BlockPosition offset(BlockPosition position, int dx, int dz) {
        return new BlockPosition(position.x() + dx, position.y(), position.z() + dz);
    }

    private static int stair(String block, String facing, String half, String shape) {
        int state = REGISTRY.block(block).defaultState();
        state = REGISTRY.with(state, "facing", facing);
        state = REGISTRY.with(state, "half", half);
        return REGISTRY.with(state, "shape", shape);
    }

    private static boolean intersects(List<WorldCollisionBox> boxes, WorldCollisionBox player) {
        for (WorldCollisionBox box : boxes) {
            if (player.minX() < box.maxX()
                    && player.maxX() > box.minX()
                    && player.minY() < box.maxY()
                    && player.maxY() > box.minY()
                    && player.minZ() < box.maxZ()
                    && player.maxZ() > box.minZ()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(List<WorldCollisionBox> boxes, double x, double y, double z) {
        for (WorldCollisionBox box : boxes) {
            if (x > box.minX()
                    && x < box.maxX()
                    && y > box.minY()
                    && y < box.maxY()
                    && z > box.minZ()
                    && z < box.maxZ()) {
                return true;
            }
        }
        return false;
    }
}
