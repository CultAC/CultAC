package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;

/**
 * A client whose chorus plants carry no connections connects a chorus plant on each side with a chorus plant or flower
 * beside it, and below also to end stone, whatever the Java state names. A chorus plant placed alone with east, west and
 * up arms landed a Bedrock player at 0.875 above its floor, the top of its core, not on the up arm at 1.0.
 */
class BedrockChorusNeighbourConnectionTest {
    private static final BlockRegistry REGISTRY = DataTables.defaults().registry();
    private static final BlockPosition PLANT = new BlockPosition(20135, 120, 20174);

    @Test
    void aLoneChorusPlantIsItsCore() {
        int state = plant(Map.of("east", "true", "west", "true", "up", "true"));
        WorldCollisionBox box = single(Map.of(PLANT, state));
        // The captured landing on it.
        assertEquals(120.875, box.maxY(), 1e-9);
        assertEquals(PLANT.x() + 0.125, box.minX(), 1e-9);
        assertEquals(PLANT.x() + 0.875, box.maxX(), 1e-9);
        assertEquals(121.0, ownState(state).maxY(), 1e-9, "the arms its state names");
    }

    @Test
    void eachSideConnectsToAChorusPlantOrFlower() {
        int state = plant(Map.of());
        String flower = "minecraft:chorus_flower";
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(PLANT, state));
        world.put(offset(0, 1, 0), plant(Map.of()));
        world.put(offset(1, 0, 0), REGISTRY.block(flower).defaultState());
        world.put(offset(0, 0, -1), REGISTRY.block("minecraft:end_stone").defaultState());
        WorldCollisionBox box = single(world);
        assertEquals(121.0, box.maxY(), 1e-9, "up to a plant");
        assertEquals(PLANT.x() + 1.0, box.maxX(), 1e-9, "east to a flower");
        assertEquals(PLANT.z() + 0.125, box.minZ(), 1e-9, "end stone beside does not connect");
        assertEquals(PLANT.x() + 0.125, box.minX(), 1e-9);
        assertEquals(PLANT.y() + 0.125, box.minY(), 1e-9);
    }

    @Test
    void downConnectsToEndStone() {
        Map<BlockPosition, Integer> world = new HashMap<>(Map.of(PLANT, plant(Map.of())));
        world.put(offset(0, -1, 0), REGISTRY.block("minecraft:end_stone").defaultState());
        assertEquals(PLANT.y(), single(world).minY(), 1e-9);
        world.put(offset(0, -1, 0), REGISTRY.block("minecraft:stone").defaultState());
        assertEquals(PLANT.y() + 0.125, single(world).minY(), 1e-9);
    }

    private static WorldCollisionBox single(Map<BlockPosition, Integer> world) {
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
                        true));
        List<WorldCollisionBox> boxes = new BedrockCollisionWorldBuilder(
                        BedrockCollisionOverrideCatalog.nativeFallback(), context)
                .block(PLANT, world.get(PLANT), BedrockCollisionShapeQuery.NONE)
                .collisionBoxes();
        assertEquals(1, boxes.size());
        return boxes.getFirst();
    }

    private static WorldCollisionBox ownState(int state) {
        return new BedrockCollisionWorldBuilder(BedrockCollisionOverrideCatalog.nativeFallback())
                .block(PLANT, state, BedrockCollisionShapeQuery.NONE)
                .collisionBoxes()
                .getFirst();
    }

    private static BlockPosition offset(int dx, int dy, int dz) {
        return new BlockPosition(PLANT.x() + dx, PLANT.y() + dy, PLANT.z() + dz);
    }

    private static int plant(Map<String, String> sides) {
        int state = REGISTRY.block("minecraft:chorus_plant").defaultState();
        for (String side : List.of("north", "south", "east", "west", "up", "down")) {
            state = REGISTRY.with(state, side, sides.getOrDefault(side, "false"));
        }
        return state;
    }
}
