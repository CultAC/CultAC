package ac.cult.cultac.bedrock.prediction.simulation.frame;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BedrockBlockSurfaceMovementTest {
    private static final PlayerDimensionsState DIMENSIONS = new PlayerDimensionsState(
        0.6000000238418579D,
        1.7999999523162842D
    );
    private static final HoneySlideState HONEY_WALL = HoneySlideState.active(
        DIMENSIONS.width(),
        List.of(new BlockPosition(284, 84, -93))
    );
    private static final Vec3d FALLING_VELOCITY = new Vec3d(0.014D, -0.196D, 0.011D);

    @Test
    public void subMillimeterHoneyBoundaryOverlapDoesNotSlide() {
        Vec3d feet = new Vec3d(
            283.8411560058594D,
            84.85671997070312D,
            -91.70082092285156D
        );

        assertFalse(BedrockBlockSurfaceMovement.isHoneySliding(
            FALLING_VELOCITY, HONEY_WALL, feet, DIMENSIONS
        ));
    }

    @Test
    public void honeyOverlapBeyondEntityInsideContractionSlides() {
        Vec3d feet = new Vec3d(283.8411560058594D, 84.85671997070312D, -91.7011D);

        assertTrue(BedrockBlockSurfaceMovement.isHoneySliding(
            FALLING_VELOCITY, HONEY_WALL, feet, DIMENSIONS
        ));
    }
}
