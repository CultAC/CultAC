package ac.cult.cultac.bedrock.prediction.world;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockClimbableContactTest {
    @Test
    public void adjacentClimbableDoesNotStopGlidingThroughAabbOverlap() {
        PlacedBlockCollision caveVines = PlacedBlockCollision.manual(
            new BlockPosition(0, 4, 0),
            "minecraft:cave_vines[age=0,berries=false]",
            "minecraft:cave_vines",
            List.of(),
            Set.of(BlockContactBehavior.CLIMBABLE)
        );

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
            new BlockCollisionWorld(List.of(caveVines)),
            new Vec3d(1.1D, 4.2D, 0.5D),
            0.6D,
            1.8D
        );

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
                new BlockCollisionWorld(List.of(scaffolding)),
                new Vec3d(1.1D, 4.2D, 0.5D),
                0.6D,
                1.8D);

        assertTrue(contact.climbing());
        assertTrue(contact.scaffolding());
        assertTrue(contact.descendAllowed());
    }

    @Test
    public void powderSnowPublishesBlockClimberFlagFromActorFootprint() {
        PlacedBlockCollision powderSnow = PlacedBlockCollision.manual(
            new BlockPosition(0, 4, 0),
            "minecraft:powder_snow",
            "minecraft:powder_snow",
            List.of(),
            Set.of(BlockContactBehavior.POWDER_SNOW)
        );

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
            new BlockCollisionWorld(List.of(powderSnow)),
            new Vec3d(1.05D, 4.2D, 0.5D),
            0.6D,
            1.8D,
            true
        );

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
            Set.of(BlockContactBehavior.POWDER_SNOW)
        );

        BedrockClimbableContact contact = BedrockClimbableContact.fromBlockWorld(
            new BlockCollisionWorld(List.of(powderSnow)),
            new Vec3d(0.5D, 4.0D, 0.5D),
            0.6D,
            1.5D,
            true
        );

        assertFalse(contact.ascendableBlock());
        assertTrue(contact.descendAllowed());
    }
}
