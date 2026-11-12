package ac.cult.cultac.bedrock.prediction.world;

import static org.junit.Assert.assertEquals;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.JavaStateProperties;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PlacedBlockCollisionFacingTest {
    @Test
    public void sampledStairCarriesItsFacing() {
        JavaStateProperties properties = sampled("minecraft:oak_stairs");
        assertEquals(2, properties.stairFacing());
        assertEquals(JavaStateProperties.NO_FACING, properties.shelfFacing());
    }

    @Test
    public void sampledShelfCarriesItsFacing() {
        JavaStateProperties properties = sampled("minecraft:oak_shelf");
        assertEquals(2, properties.shelfFacing());
        assertEquals(JavaStateProperties.NO_FACING, properties.stairFacing());
    }

    @Test
    public void sampledFacingBlockOutsideBothFamiliesCarriesNoFacing() {
        JavaStateProperties properties = sampled("minecraft:furnace");
        assertEquals(JavaStateProperties.NO_FACING, properties.stairFacing());
        assertEquals(JavaStateProperties.NO_FACING, properties.shelfFacing());
    }

    /** The default state, which faces north, of the given block. */
    private static JavaStateProperties sampled(String key) {
        BlockDefinition block = DataTables.defaults().registry().blocks().stream()
                .filter(definition -> definition.key().equals(key))
                .findFirst()
                .orElseThrow();
        return PlacedBlockCollision.sampled(
                        new BlockPosition(0, 0, 0), key, block.defaultState(), key, Map.of(), List.of())
                .javaStateProperties();
    }
}
