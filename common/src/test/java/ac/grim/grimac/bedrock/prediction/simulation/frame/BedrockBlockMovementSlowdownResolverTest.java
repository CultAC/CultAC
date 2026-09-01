package ac.grim.grimac.bedrock.prediction.simulation.frame;

import ac.grim.grimac.bedrock.prediction.geometry.BlockPosition;
import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision;
import ac.grim.grimac.bedrock.prediction.world.PlacedBlockCollision.BlockContactBehavior;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class BedrockBlockMovementSlowdownResolverTest {
    private static final double PLAYER_WIDTH = 0.6D;
    private static final double PLAYER_HEIGHT = 1.8D;

    @Test
    public void subMillimeterCobwebBoundaryOverlapDoesNotApplySlowdown() {
        BlockMovementSlowdownState slowdown = slowdownAt(
            new Vec3d(292.0312805175781D, 82.01536560058594D, -91.29927825927734D)
        );

        assertEquals(BlockMovementSlowdownState.NONE, slowdown);
    }

    @Test
    public void cobwebOverlapBeyondEntityInsideContractionAppliesSlowdown() {
        BlockMovementSlowdownState slowdown = slowdownAt(
            new Vec3d(292.0312805175781D, 82.01536560058594D, -91.298D)
        );

        assertEquals(BlockMovementSlowdownState.COBWEB, slowdown);
    }

    private static BlockMovementSlowdownState slowdownAt(Vec3d feet) {
        return BedrockBlockMovementSlowdownResolver.fromBlockWorld(
            PLAYER_WIDTH,
            PLAYER_HEIGHT,
            feet,
            cobwebWorld(),
            false
        );
    }

    private static BlockCollisionWorld cobwebWorld() {
        BlockPosition position = new BlockPosition(292, 82, -91);
        return new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
            position,
            "minecraft:cobweb",
            "minecraft:cobweb",
            List.of(),
            Set.of(BlockContactBehavior.COBWEB)
        )));
    }
}
