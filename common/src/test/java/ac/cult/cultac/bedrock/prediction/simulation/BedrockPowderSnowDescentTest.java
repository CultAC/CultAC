package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.geometry.BedrockCollisionWorldBuilder;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockSnapshotResolver;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.utils.collisions.BedrockClientBlockShapeMappings;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockPowderSnowDescentTest {
    @Test
    public void sneakingDownIntoPowderSnowFallsOnWithGravity() {
        // Sneaking down from the top of powder snow in leather boots the client sinks 0.15 and reports
        // (-0.15 - 0.08) * 0.98 = -0.2254 after it: gravity follows the descent, unlike inside scaffolding.
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        int powderSnow = DataTables.defaults().registry().block("minecraft:powder_snow").defaultState();
        var onTop = BedrockCollisionShapeQuery.actor(
                new WorldCollisionBox(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D), false, true, 0.0F);
        var world = new BlockCollisionWorld(List.of(
                builder.block(new BlockPosition(0, 0, 0), powderSnow, onTop),
                builder.block(new BlockPosition(0, -1, 0), powderSnow, onTop)));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                new EquipmentState(0, 0, 0, true, false),
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        Vec3d feet = new Vec3d(0.5D, 1.0D, 0.5D);
        BedrockMovementState standing = BedrockMovementState.fromPhysicalFeet(
                        feet, new Vec3d(0.0D, -0.0784D, 0.0D), BedrockInputFrame.idle(1L), BedrockCollisionFlags.ON_GROUND)
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(world, feet, 0.6D, 1.8D, true));
        BedrockInputFrame sneak = new BedrockInputFrame(
                2L, 0.0F, 0.0F, false, true, false, Set.of("SNEAKING", "START_SNEAKING", "SNEAK_DOWN", "WANT_DOWN"));
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), standing, sneak);

        var result = BedrockSimulation.candidates(new BedrockSimulation.Input(
                        standing,
                        sneak,
                        sneak.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();

        assertEquals(0.85D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-0.2254D, result.predictedVelocity().y(), 1.0E-6D);
    }
}
