package ac.cult.cultac.utils.collisions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionOverrideCatalog;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shared collision collector gives a Bedrock player the engine world's model of a moving piston. A run printed
 * Java's retracting head plate, x 0.75 to 1.0 of its cell, as the block a Bedrock player was inside, in a cell the
 * client already had as air.
 */
class BedrockMovingPistonShapeTest {
    @Test
    void aMovingPistonCollidesAsTheEngineWorldModelsIt() {
        BedrockCollisionOverrideCatalog catalog = BedrockCollisionOverrideCatalog.nativeFallback();
        var registry = DataTables.defaults().registry();
        int movingPiston = registry.block("minecraft:moving_piston").defaultState();
        assertTrue(BlockIds.is(movingPiston, BlockIds.MOVING_PISTON));

        var entry = BedrockClientBlockShapeMappings.build(catalog).entry(movingPiston);
        assertNotNull(entry);
        assertFalse(entry.dynamicMovement());
        var position = new BlockPosition(0, 0, 0);
        boolean engineCollides = new BedrockCollisionWorldBuilder(catalog)
                .build(Map.of(position, movingPiston))
                .blockAt(position)
                .map(block -> !block.collisionBoxes().isEmpty())
                .orElse(false);
        assertFalse(engineCollides);
        assertTrue(entry.movement().isNull());

        // The mapping replaces the Java shape the collector would otherwise fall through to.
        var javaHeadPlate = new SimpleCollisionBox(0.75, 0, 0, 1, 1, 1);
        assertTrue(ClientBlockShapes.selectIfDifferent(javaHeadPlate, Optional.of(entry.movement()))
                .isNull());
    }
}
