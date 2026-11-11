package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext;
import ac.cult.cultac.bedrock.prediction.api.BedrockCollisionShapeQuery;
import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
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
import ac.cult.cultac.bedrock.prediction.world.BedrockClimberFlags;
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
    private static final BedrockClimberFlags CLEARED_FLAGS = new BedrockClimberFlags(false, false, false, false);
    private static final BedrockInputFrame SNEAK = new BedrockInputFrame(
            2L, 0.0F, 0.0F, false, true, false, Set.of("SNEAKING", "START_SNEAKING", "SNEAK_DOWN", "WANT_DOWN"));
    private static final BedrockInputFrame HELD_SNEAK =
            new BedrockInputFrame(3L, 0.0F, 0.0F, false, true, false, Set.of("SNEAKING", "SNEAK_DOWN", "WANT_DOWN"));
    private static final BedrockInputFrame HELD_JUMP =
            new BedrockInputFrame(3L, 0.0F, 0.0F, true, false, false, Set.of("JUMPING", "JUMP_DOWN", "WANT_UP"));

    @Test
    public void sneakingDownIntoPowderSnowFallsOnWithGravity() {
        // Sneaking down from the top of powder snow in leather boots the client sinks 0.15 and reports
        // (-0.15 - 0.08) * 0.98 = -0.2254 after it: gravity follows the descent, unlike inside scaffolding.
        var result = simulate(state(new Vec3d(0.5D, 1.0D, 0.5D), -0.0784D), SNEAK);

        assertEquals(0.85D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(-0.2254D, result.predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void sneakDescentInsidePowderSnowSinksPointTwoTwoFive() {
        // The descend action's -0.15 times the 1.5 vertical powder snow multiplier.
        var result = simulate(state(new Vec3d(0.5D, 0.85D, 0.5D), -0.2254D), HELD_SNEAK);

        assertEquals(0.85D - 0.225D, result.predictedPosition().y(), 1.0E-6D);
    }

    @Test
    public void sneakDescentAfterAFlagsUpdateFallsAtTheClimbClamp() {
        // A flags update for the player, received after the first descent tick, clears the descendable flag:
        // no descend action runs, so -0.2254 is clamped to -0.2 and moves -0.2 * 1.5 = -0.3.
        var result =
                simulate(state(new Vec3d(0.5D, 0.85D, 0.5D), -0.2254D).withClimberFlags(CLEARED_FLAGS), HELD_SNEAK);

        assertEquals(0.85D - 0.3D, result.predictedPosition().y(), 1.0E-6D);
    }

    @Test
    public void heldJumpInsidePowderSnowRisesPointTwoTwoFive() {
        // The ascendable block jump's 0.15 times 1.5.
        var result = simulate(state(new Vec3d(0.5D, 0.225D, 0.5D), -0.0784D), HELD_JUMP);

        assertEquals(0.225D + 0.225D, result.predictedPosition().y(), 1.0E-6D);
    }

    @Test
    public void heldJumpAfterAFlagsUpdateClimbsAtTheLadderSpeed() {
        // With the ascendable flag cleared the jump takes the powder snow climb branch: 0.2 * 1.5 = 0.3.
        var result =
                simulate(state(new Vec3d(0.5D, 0.225D, 0.5D), -0.0784D).withClimberFlags(CLEARED_FLAGS), HELD_JUMP);

        assertEquals(0.225D + 0.3D, result.predictedPosition().y(), 1.0E-6D);
    }

    private static BedrockMovementState state(Vec3d feet, double velocityY) {
        return BedrockMovementState.fromPhysicalFeet(
                        feet,
                        new Vec3d(0.0D, velocityY, 0.0D),
                        BedrockInputFrame.idle(1L),
                        feet.y() == 1.0D ? BedrockCollisionFlags.ON_GROUND : BedrockCollisionFlags.AIR)
                .withClimbableContact(BedrockClimbableContact.fromBlockWorld(WORLD, feet, 0.6D, 1.8D, true));
    }

    private static final BlockCollisionWorld WORLD = powderSnowColumn();

    private static BlockCollisionWorld powderSnowColumn() {
        var builder = new BedrockCollisionWorldBuilder(
                BedrockClientBlockShapeMappings.catalog(), BedrockBlockShapeContext.NONE);
        int powderSnow = DataTables.defaults().registry().block("minecraft:powder_snow").defaultState();
        var onTop = BedrockCollisionShapeQuery.actor(
                new WorldCollisionBox(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D), false, true, 0.0F);
        return new BlockCollisionWorld(List.of(
                builder.block(new BlockPosition(0, 0, 0), powderSnow, onTop),
                builder.block(new BlockPosition(0, -1, 0), powderSnow, onTop)));
    }

    private static BedrockMovementResult simulate(
            BedrockMovementState previous, BedrockInputFrame frame) {
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, WORLD),
                new EquipmentState(0, 0, 0, true, false),
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        var snapshot = BedrockSnapshotResolver.forState(BedrockWorldSnapshot.fromContext(context), previous, frame);
        return BedrockSimulation.candidates(new BedrockSimulation.Input(
                        previous,
                        frame,
                        frame.intent(),
                        snapshot,
                        true,
                        BedrockSimulation.DEFAULT_MAX_AUTO_STEP,
                        BedrockMobJumpComponentState.DEFAULT,
                        true,
                        false,
                        Vec3d.ZERO))
                .getFirst()
                .movementResult();
    }
}
