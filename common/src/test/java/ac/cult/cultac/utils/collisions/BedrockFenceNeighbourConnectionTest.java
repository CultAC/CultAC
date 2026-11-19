package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockBlockConnections;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A client whose fences, panes and bars carry no connection state connects them on each side where the block beside
 * them has no connection rule, or a rule that accepts their connector type on the face that points back. A fence line
 * toggled while a Bedrock player walked into it stopped the player at arms its Java state named and the client did not
 * build.
 */
class BedrockFenceNeighbourConnectionTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPosition AT = new BlockPosition(100, 64, 100);

    @Test
    void aLoneFenceIsItsPostWhateverItsStateNames() {
        int fence = state("minecraft:oak_fence", Map.of("north", "true", "east", "true"));
        assertEquals(sides(), sides(Map.of(AT, fence)));
    }

    @Test
    void aFenceConnectsToBlocksWithoutARuleAndToFencesOfItsKind() {
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(AT, state("minecraft:oak_fence", Map.of())));
        world.put(north(), state("minecraft:stone", Map.of()));
        world.put(south(), state("minecraft:spruce_fence", Map.of()));
        world.put(west(), state("minecraft:nether_brick_fence", Map.of()));
        world.put(east(), state("minecraft:cobblestone_wall", Map.of()));
        assertEquals(sides("north", "south"), sides(world));
    }

    @Test
    void aFenceConnectsToAStairOnlyAtItsBackAndToAGateOnlyAlongItsSpan() {
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(AT, state("minecraft:oak_fence", Map.of())));
        // A south-facing stair north of the fence turns its back, the south face, to it; one south of it does not.
        world.put(north(), state("minecraft:oak_stairs", Map.of("facing", "south")));
        world.put(south(), state("minecraft:oak_stairs", Map.of("facing", "south")));
        world.put(west(), state("minecraft:oak_fence_gate", Map.of("facing", "north")));
        world.put(east(), state("minecraft:oak_fence_gate", Map.of("facing", "east")));
        assertEquals(sides("north", "west"), sides(world));
    }

    @Test
    void aPaneConnectsToPanesWallsAndRuleLessBlocksButNotToFencesOrLeaves() {
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(AT, state("minecraft:glass_pane", Map.of())));
        world.put(north(), state("minecraft:iron_bars", Map.of()));
        world.put(south(), state("minecraft:oak_fence", Map.of()));
        world.put(west(), state("minecraft:glass", Map.of()));
        world.put(east(), state("minecraft:oak_leaves", Map.of()));
        assertEquals(sides("north", "west"), sides(world));
    }

    private static TreeSet<String> sides(String... names) {
        return new TreeSet<>(Arrays.asList(names));
    }

    private static TreeSet<String> sides(Map<BlockPosition, Integer> world) {
        List<WorldCollisionBox> boxes = boxes(world);
        TreeSet<String> sides = new TreeSet<>();
        if (inside(boxes, 0.5, 0.05)) sides.add("north");
        if (inside(boxes, 0.5, 0.95)) sides.add("south");
        if (inside(boxes, 0.05, 0.5)) sides.add("west");
        if (inside(boxes, 0.95, 0.5)) sides.add("east");
        return sides;
    }

    private static List<WorldCollisionBox> boxes(Map<BlockPosition, Integer> world) {
        int size = REGISTRY.stateCount();
        int[] rules = new int[size];
        byte[] connectors = new byte[size];
        Arrays.fill(rules, BedrockBlockConnections.NO_RULE);
        Arrays.fill(connectors, (byte) BedrockBlockConnections.NO_CONNECTOR);
        var states = new java.util.HashSet<>(world.values());
        // Air is a block with a rule that accepts nothing.
        states.add(REGISTRY.block("minecraft:air").defaultState());
        for (int state : states) {
            String name = bedrockName(state);
            rules[state] = BedrockBlockConnections.rule(name, bedrockStates(state));
            connectors[state] = (byte) BedrockBlockConnections.connectorType(name);
        }
        var context = new BedrockBlockShapeContext(
                BedrockBlockShapeContext.NONE.shulkerBoxLift(),
                BedrockBlockShapeContext.NONE.movingBlockBoxes(),
                BedrockBlockShapeContext.NONE.pistonProgress(),
                BedrockCoordinateFrame.IDENTITY,
                BedrockBlockShapeContext.NONE.movingBlockCarried(),
                new BedrockBlockShapeContext.DerivedShapes(
                        at -> world.getOrDefault(
                                at, REGISTRY.block("minecraft:air").defaultState()),
                        false,
                        false,
                        true,
                        new BedrockBlockShapeContext.Connections(rules, connectors)));
        return new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.nativeFallback(), context)
                .block(AT, world.get(AT), BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
    }

    // The Bedrock blocks Geyser sends for these Java blocks.
    private static String bedrockName(int state) {
        String key = REGISTRY.block(state).key();
        return switch (key) {
            case "minecraft:oak_fence_gate" -> "minecraft:fence_gate";
            case "minecraft:oak_leaves" -> "minecraft:oak_leaves";
            default -> key;
        };
    }

    private static Map<String, Object> bedrockStates(int state) {
        String key = REGISTRY.block(state).key();
        Map<String, Object> states = new HashMap<>();
        if (key.endsWith("_stairs")) {
            String facing = REGISTRY.value(state, "facing");
            states.put(
                    "weirdo_direction",
                    switch (facing) {
                        case "east" -> 0;
                        case "west" -> 1;
                        case "south" -> 2;
                        default -> 3;
                    });
        } else if (key.endsWith("_fence_gate")) {
            states.put("minecraft:cardinal_direction", REGISTRY.value(state, "facing"));
        }
        return states;
    }

    private static int state(String block, Map<String, String> properties) {
        int state = REGISTRY.block(block).defaultState();
        for (var entry : properties.entrySet()) state = REGISTRY.with(state, entry.getKey(), entry.getValue());
        return state;
    }

    private static BlockPosition north() {
        return new BlockPosition(AT.x(), AT.y(), AT.z() - 1);
    }

    private static BlockPosition south() {
        return new BlockPosition(AT.x(), AT.y(), AT.z() + 1);
    }

    private static BlockPosition west() {
        return new BlockPosition(AT.x() - 1, AT.y(), AT.z());
    }

    private static BlockPosition east() {
        return new BlockPosition(AT.x() + 1, AT.y(), AT.z());
    }

    private static boolean inside(List<WorldCollisionBox> boxes, double localX, double localZ) {
        double x = AT.x() + localX, z = AT.z() + localZ, y = AT.y() + 0.5;
        for (WorldCollisionBox box : boxes) {
            if (x > box.minX()
                    && x < box.maxX()
                    && y > box.minY()
                    && y < box.maxY()
                    && z > box.minZ()
                    && z < box.maxZ()) return true;
        }
        return false;
    }
}
