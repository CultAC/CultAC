package ac.cult.cultac.bedrock.prediction.world;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockClimbableContactTest {
    @Test
    public void adjacentClimbableDoesNotStopGlidingThroughAabbOverlap() {
        PlacedBlockCollision caveVines = PlacedBlockCollision.manual(
                new BlockPosition(0, 4, 0),
                "minecraft:cave_vines[age=0,berries=false]",
                "minecraft:cave_vines",
                List.of(),
                Set.of(BlockContactBehavior.CLIMBABLE));

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
                new BlockCollisionWorld(List.of(caveVines)), new Vec3d(1.1D, 4.2D, 0.5D), 0.6D, 1.8D);

        assertFalse(contact.climbing());
    }

    @Test
    public void scaffoldingBehaviorTakesPrecedenceOverGenericClimbableTag() {
        BlockPosition position = new BlockPosition(0, 4, 0);
        PlacedBlockCollision scaffolding = PlacedBlockCollision.manual(
                position,
                "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                "minecraft:scaffolding",
                List.of(),
                Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING));

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
                new BlockCollisionWorld(List.of(scaffolding)), new Vec3d(1.1D, 4.2D, 0.5D), 0.6D, 1.8D);

        assertTrue(contact.climbing());
        assertTrue(contact.scaffolding());
        // The row below the feet holds no scaffolding to fall through.
        assertFalse(contact.descendAllowed());
    }

    @Test
    public void powderSnowPublishesBlockClimberFlagFromActorFootprint() {
        PlacedBlockCollision powderSnow = PlacedBlockCollision.manual(
                new BlockPosition(0, 4, 0),
                "minecraft:powder_snow",
                "minecraft:powder_snow",
                List.of(),
                Set.of(BlockContactBehavior.POWDER_SNOW));

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
                new BlockCollisionWorld(List.of(powderSnow)), new Vec3d(1.05D, 4.2D, 0.5D), 0.6D, 1.8D, true);

        assertTrue(contact.ascendableBlock());
        assertFalse(contact.climbing());
        assertFalse(contact.scaffolding());
    }

    @Test
    public void powderSnowBelowPublishesDescendAllowedFlag() {
        PlacedBlockCollision powderSnow = PlacedBlockCollision.manual(
                new BlockPosition(0, 3, 0),
                "minecraft:powder_snow",
                "minecraft:powder_snow",
                List.of(),
                Set.of(BlockContactBehavior.POWDER_SNOW));

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
                new BlockCollisionWorld(List.of(powderSnow)), new Vec3d(0.5D, 4.0D, 0.5D), 0.6D, 1.5D, true);

        assertFalse(contact.ascendableBlock());
        assertTrue(contact.descendAllowed());
    }

    // Scaffolding under the feet lets a sneaking player through only where a block holds it up.
    @Test
    public void scaffoldingHangingOverAirOrWaterAllowsNoDescent() {
        assertFalse(overScaffolding(null).descendAllowed());
        assertFalse(overScaffolding("minecraft:water").descendAllowed());
        assertFalse(overScaffolding("minecraft:flowing_water").descendAllowed());
        assertTrue(overScaffolding(null).overScaffolding());
    }

    @Test
    public void scaffoldingRestingOnABlockAllowsDescent() {
        assertTrue(overScaffolding("minecraft:stone").descendAllowed());
        assertTrue(overScaffolding("minecraft:scaffolding").descendAllowed());
        assertTrue(overScaffolding("minecraft:lava").descendAllowed());
    }

    /** The contact of a player standing on scaffolding at y 3 that rests on {@code below}, or on nothing. */
    private static BedrockClimbableContact overScaffolding(String below) {
        List<PlacedBlockCollision> blocks = new ArrayList<>();
        blocks.add(PlacedBlockCollision.manual(
                new BlockPosition(0, 3, 0),
                "minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]",
                "minecraft:scaffolding",
                List.of(),
                Set.of(BlockContactBehavior.CLIMBABLE, BlockContactBehavior.SCAFFOLDING)));
        if (below != null) {
            blocks.add(PlacedBlockCollision.manual(new BlockPosition(0, 2, 0), below, below, List.of()));
        }
        return BedrockClimbableContact.fromBlockWorld(
                new BlockCollisionWorld(blocks), new Vec3d(0.5D, 4.0D, 0.5D), 0.6D, 1.8D);
    }
}
