package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementMath;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ElytraTransform;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.ValidMovements;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class JavaGlidePointCandidatesTest {
    /** Neither a target inside the two results' box nor a pusher's old Y radius is an actual glide result. */
    @Test
    void eachTrigChoiceKeepsItsWholeVectorRegardlessOfTheTarget() throws Exception {
        try (var f = new JavaTickFixture()) {
            var context = mock(SimulationContext.class);
            when(context.usesFallFlyingMovement()).thenReturn(true);
            when(context.getWorldData()).thenReturn(mock(WorldData.class));
            when(context.getXRot()).thenReturn(17.293F);
            when(context.getYRot()).thenReturn(-33.713F);
            var result = mock(PredictionResult.class);
            when(result.getSimulationContext()).thenReturn(context);
            when(result.getPossibleGravity(anyDouble(), anyDouble())).thenReturn(java.util.List.of(0.08));
            var valid = mock(ValidMovements.class);
            when(valid.getCollisionIgnoredMaxStartingVelExtents())
                    .thenReturn(new SimpleCollisionBox(-0.2, -0.1, -0.2, 0.2, 0.1, 0.2));
            var handler = new ElytraTransform();
            var vanilla = new JavaMovementMath(new PredVector(new Vec3(0.01, -0.01, 0.01)), true);
            var fast = new JavaMovementMath(new PredVector(vanilla), false);
            Vec3 normal = handler.handleUncertainty(f.player, valid, result, context, null, vanilla, Vec3.ZERO);
            Vec3 alternate = handler.handleUncertainty(f.player, valid, result, context, null, fast, Vec3.ZERO);
            assertTrue(normal.y < 0 && alternate.y < 0);
            assertTrue(normal.distanceTo(alternate) > 1e-10);
            Vec3 fabricated = new Vec3((normal.x + alternate.x) * 0.5, 0, (normal.z + alternate.z) * 0.5);
            assertEquals(
                    normal, handler.handleUncertainty(f.player, valid, result, context, null, vanilla, fabricated));
            assertEquals(
                    alternate, handler.handleUncertainty(f.player, valid, result, context, null, fast, fabricated));
            assertTrue(f.player.trigHandler.isVanillaMath());
        }
    }
}
