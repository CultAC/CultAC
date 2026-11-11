package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A Bedrock stair carries its corner in its own state, translated one to one from the Java stair shape, and the
 * client collides with that corner whatever its neighbours are. An east-facing inner_left stone brick stair with no
 * stair beside it stopped a Bedrock player at its west face 0.5 above the floor, where a straight stair has only its
 * lower half.
 */
class BedrockStairCornerShapeTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPosition ORIGIN = new BlockPosition(0, 0, 0);
    private static final BedrockCollisionWorldBuilder BUILDER =
            new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.nativeFallback());

    // Raised quarters of the step half by facing and shape: N/S is the z half, W/E the x half.
    private static final Map<String, Map<String, Set<String>>> RAISED = Map.of(
            "north",
                    Map.of(
                            "straight", Set.of("NW", "NE"),
                            "inner_left", Set.of("NW", "NE", "SW"),
                            "inner_right", Set.of("NW", "NE", "SE"),
                            "outer_left", Set.of("NW"),
                            "outer_right", Set.of("NE")),
            "south",
                    Map.of(
                            "straight", Set.of("SW", "SE"),
                            "inner_left", Set.of("SW", "SE", "NE"),
                            "inner_right", Set.of("SW", "SE", "NW"),
                            "outer_left", Set.of("SE"),
                            "outer_right", Set.of("SW")),
            "east",
                    Map.of(
                            "straight", Set.of("NE", "SE"),
                            "inner_left", Set.of("NE", "SE", "NW"),
                            "inner_right", Set.of("NE", "SE", "SW"),
                            "outer_left", Set.of("NE"),
                            "outer_right", Set.of("SE")),
            "west",
                    Map.of(
                            "straight", Set.of("NW", "SW"),
                            "inner_left", Set.of("NW", "SW", "SE"),
                            "inner_right", Set.of("NW", "SW", "NE"),
                            "outer_left", Set.of("SW"),
                            "outer_right", Set.of("NW")));

    @Test
    void aStairRaisesTheQuartersOfItsCorner() {
        for (var facing : RAISED.entrySet()) {
            for (var shape : facing.getValue().entrySet()) {
                for (String half : List.of("bottom", "top")) {
                    int state = stair("minecraft:stone_brick_stairs", facing.getKey(), half, shape.getKey());
                    List<WorldCollisionBox> boxes = boxes(state);
                    double slabY = half.equals("bottom") ? 0.25 : 0.75;
                    double stepY = 1.0 - slabY;
                    for (String quarter : List.of("NW", "NE", "SW", "SE")) {
                        double x = quarter.endsWith("W") ? 0.25 : 0.75;
                        double z = quarter.startsWith("N") ? 0.25 : 0.75;
                        String label = facing.getKey() + " " + shape.getKey() + " " + half + " " + quarter;
                        assertTrue(inside(boxes, x, slabY, z), label + " slab");
                        assertEquals(shape.getValue().contains(quarter), inside(boxes, x, stepY, z), label + " step");
                    }
                }
            }
        }
    }

    @Test
    void everyStairStateBuildsItsCorner() {
        int checked = 0;
        for (BlockDefinition block : REGISTRY.blocks()) {
            if (!BlockFamilies.STAIR.test(block.firstState())) {
                continue;
            }
            for (int state = block.firstState(); state < block.firstState() + block.stateCount(); state++) {
                String shape = REGISTRY.value(state, "shape");
                int expectedBoxes = shape.startsWith("inner") ? 3 : 2;
                assertEquals(expectedBoxes, boxes(state).size(), block.key() + " " + shape);
                checked++;
            }
        }
        assertTrue(checked > 1000, "stair states checked: " + checked);
    }

    private static int stair(String block, String facing, String half, String shape) {
        int state = REGISTRY.block(block).defaultState();
        state = REGISTRY.with(state, "facing", facing);
        state = REGISTRY.with(state, "half", half);
        return REGISTRY.with(state, "shape", shape);
    }

    private static List<WorldCollisionBox> boxes(int state) {
        return BUILDER.block(ORIGIN, state, BedrockCollisionShapeQuery.NONE).collisionBoxes();
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
