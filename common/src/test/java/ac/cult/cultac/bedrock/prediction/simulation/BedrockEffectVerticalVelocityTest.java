package ac.cult.cultac.bedrock.prediction.simulation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
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
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockTravelInput;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class BedrockEffectVerticalVelocityTest {
    private static final Vec3d ON_FLOOR = new Vec3d(0.5D, 1.0D, 0.5D);
    private static final Vec3d RESTING = new Vec3d(0.0D, -0.0784D, 0.0D);
    private static final BedrockEffectState LEVITATION_I = BedrockEffectState.NONE.withLevitationLevel(1);
    private static final BedrockEffectState LEVITATION_II = BedrockEffectState.NONE.withLevitationLevel(2);
    private static final BedrockEffectState SLOW_FALLING = BedrockEffectState.NONE.withSlowFalling(true);

    @Test
    public void levitationOnTheGroundStartsFromTheCollidedVelocity() {
        // The client reports 0.0098 = (0 * 0.8 + 0.01) * 0.98 on its first levitated tick on the ground.
        BedrockMovementResult result = move(grounded(), BedrockInputFrame.idle(2L), LEVITATION_I, false);

        assertEquals(ON_FLOOR.y(), result.predictedPosition().y(), 0.0D);
        assertEquals(0.0098D, result.predictedVelocity().y(), 1.0E-7D);
    }

    @Test
    public void levitationOnAJumpTickStartsFromTheJumpVelocity() {
        // The client rises 0.33908 = (0.42 * 0.8 + 0.01) * 0.98 on the tick after a levitated jump.
        BedrockInputFrame jump =
                new BedrockInputFrame(2L, 0.0F, 0.0F, true, false, false, Set.of("JUMPING", "START_JUMPING"));

        BedrockMovementResult result = move(grounded(), jump, LEVITATION_I, false);

        assertEquals(ON_FLOOR.y() + 0.42D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.33908D, result.predictedVelocity().y(), 1.0E-6D);
    }

    @Test
    public void slowFallingOnTheGroundTakesFullGravity() {
        // Standing with slow falling the client keeps reporting -0.0784, not the reduced -0.0098.
        BedrockMovementResult standing = move(grounded(), BedrockInputFrame.idle(2L), SLOW_FALLING, false);

        assertEquals(-0.0784D, standing.predictedVelocity().y(), 1.0E-7D);

        BedrockMovementState landing = BedrockMovementState.fromPhysicalFeet(
                ON_FLOOR.add(new Vec3d(0.0D, 0.1D, 0.0D)),
                new Vec3d(0.0D, -0.3D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        BedrockMovementResult landed = move(landing, BedrockInputFrame.idle(2L), SLOW_FALLING, false);

        assertEquals(ON_FLOOR.y(), landed.predictedPosition().y(), 1.0E-9D);
        assertEquals(-0.0784D, landed.predictedVelocity().y(), 1.0E-7D);
    }

    @Test
    public void slowFallingStillReducesGravityInFreeFall() {
        BedrockMovementState falling = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 20.0D, 0.5D),
                new Vec3d(0.0D, -0.3D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);

        BedrockMovementResult result = move(falling, BedrockInputFrame.idle(2L), SLOW_FALLING, false);

        assertEquals((-0.3D - 0.01D) * 0.98D, result.predictedVelocity().y(), 1.0E-7D);
    }

    @Test
    public void levitationStillAppliesAfterAGlideMove() {
        // Two captured glide ticks with Levitation II, looking level along -Z. The client moved 0.57258 and
        // 0.40403 down and reported 0.8 * move + 0.02 after each, with no gravity or drag.
        Vec3d start = new Vec3d(0.0D, -0.6291878D, -0.18807264D);

        BedrockMovementResult first = move(gliding(start), glideFrame(), LEVITATION_II, true);

        assertTrue(first.selectedGlidingTravel());
        assertEquals(-0.57258D, moveY(first), 1.0E-5D);
        assertEquals(-0.43806693D, first.predictedVelocity().y(), 1.0E-6D);
        assertEquals(-0.24403454D, first.predictedVelocity().z(), 1.0E-6D);

        BedrockMovementResult second =
                move(gliding(first.predictedVelocity()), glideFrame(), LEVITATION_II, true);

        assertEquals(-0.40403D, moveY(second), 2.0E-5D);
        assertEquals(-0.30321202D, second.predictedVelocity().y(), 1.0E-6D);
        assertEquals(-0.28240797D, second.predictedVelocity().z(), 1.0E-6D);
    }

    @Test
    public void slowFallingGlideDescendsWithReducedGravity() {
        // A level glide settled at -0.14968 per tick; with slow falling the client's next two moves were
        // -0.13422 and -0.12059, the 0.01 gravity term instead of 0.08.
        Vec3d settled = new Vec3d(0.0D, -0.149681D, -0.69733D);

        BedrockMovementResult first = move(gliding(settled), glideFrame(), SLOW_FALLING, true);
        BedrockMovementResult second =
                move(gliding(first.predictedVelocity()), glideFrame(), SLOW_FALLING, true);

        assertEquals(-0.13422D, moveY(first), 1.0E-5D);
        assertEquals(-0.12059D, moveY(second), 1.0E-5D);
        assertEquals(-0.149658D, moveY(move(gliding(settled), glideFrame(), BedrockEffectState.NONE, true)), 1.0E-5D);
    }

    @Test
    public void levitationAppliesToTheClimbVelocity() {
        // Walking into a ladder with Levitation I the client climbs 0.17 per tick: the 0.2 climb velocity
        // levitated as 0.2 + (0.05 - 0.2) * 0.2, with no gravity or drag after it.
        PlacedBlockCollision ladder = PlacedBlockCollision.manual(
                new BlockPosition(0, 1, 0),
                "minecraft:ladder",
                "minecraft:ladder[facing=north]",
                List.of(new WorldCollisionBox(0.0D, 1.0D, 0.8125D, 1.0D, 2.0D, 1.0D)));
        BedrockMovementState climbing = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.5D, 1.5D, 0.5124D),
                new Vec3d(0.0D, 0.17D, 0.05D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);

        BedrockMovementResult result = move(climbing, BedrockInputFrame.idle(2L), LEVITATION_I, false, ladder);

        assertEquals(1.67D, result.predictedPosition().y(), 1.0E-6D);
        assertEquals(0.17D, result.predictedVelocity().y(), 1.0E-6D);
    }

    private static double moveY(BedrockMovementResult result) {
        return result.predictedPosition().y()
                - result.previousState().physicalFeetPosition().y();
    }

    private static BedrockMovementState grounded() {
        return BedrockMovementState.fromPhysicalFeet(
                ON_FLOOR, RESTING, BedrockInputFrame.idle(1L), BedrockCollisionFlags.ON_GROUND);
    }

    private static BedrockMovementState gliding(Vec3d velocity) {
        return BedrockMovementState.fromPhysicalFeet(
                        new Vec3d(0.5D, 30.0D, 0.5D), velocity, glideFrame(), BedrockCollisionFlags.AIR)
                .withGliding(true);
    }

    private static BedrockInputFrame glideFrame() {
        return new BedrockInputFrame(2L, -180.0F, 0.0F, false, false, false, Set.of("GLIDING"));
    }

    private static BedrockMovementResult move(
            BedrockMovementState state,
            BedrockInputFrame frame,
            BedrockEffectState effects,
            boolean elytra,
            PlacedBlockCollision... extra) {
        PlacedBlockCollision floor = PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D)));
        BedrockMovementContext context = new BedrockMovementContext(
                effects,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, new BlockCollisionWorld(blocks(floor, extra))),
                new EquipmentState(0, 0, 0, false, elytra),
                EntityContactState.NONE,
                new MovementModifierState(elytra, false, false, false, 0.05D, false, false, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
        return BedrockSimulation.move(
                state,
                frame,
                context,
                BedrockTravelInput.ScaffoldingVerticalBranch.SOURCE,
                false,
                BedrockSimulation.DEFAULT_MAX_AUTO_STEP);
    }

    private static List<PlacedBlockCollision> blocks(PlacedBlockCollision floor, PlacedBlockCollision... extra) {
        List<PlacedBlockCollision> blocks = new java.util.ArrayList<>(List.of(extra));
        blocks.add(floor);
        return blocks;
    }
}
