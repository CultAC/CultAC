package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.api.BedrockMovementResult;
import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.BedrockSimulation;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.HoneySlideState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class BedrockValidationSelectionTest {
    @Test
    public void validationSelectedPositionIsNextTickBaseWithoutPacketPosition() {
        BedrockValidationSelection selection = new BedrockValidationSelection(
                new Vec3d(1.0D, 2.0D, 3.0D),
                null,
                new Vec3(0.25D, 0.5D, -0.75D),
                new Vec3d(1.25D, 2.5D, 2.25D),
                null
        );

        Vec3d nextTickBasePosition = BedrockValidationSelectedState.nextTickBasePosition(selection);
        assertEquals(new Vec3d(1.25D, 2.5D, 2.25D), nextTickBasePosition);
        assertEquals(0.875D, BedrockValidationSelectedState.displacementSquared(selection, nextTickBasePosition), 0.0D);
    }

    @Test
    public void packetPositionIsNextTickBaseWhenPresent() {
        BedrockValidationSelection selection = new BedrockValidationSelection(
                new Vec3d(1.0D, 2.0D, 3.0D),
                null,
                new Vec3(0.25D, 0.5D, -0.75D),
                new Vec3d(1.25D, 2.5D, 2.25D),
                new Vec3(1.5D, 2.25D, 3.75D)
        );

        Vec3d nextTickBasePosition = BedrockValidationSelectedState.nextTickBasePosition(selection);
        assertEquals(new Vec3d(1.5D, 2.25D, 3.75D), nextTickBasePosition);
        assertEquals(0.875D, BedrockValidationSelectedState.displacementSquared(selection, nextTickBasePosition), 0.0D);
    }

    @Test
    public void validationSelectedRawVerticalCollisionCannotManufactureGroundState() {
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(1.0D, 2.0D, 3.0D),
                new Vec3d(0.4D, -0.2D, 0.1D),
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.AIR
        );
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID())
                .rawInputFlags(inputFlag(PlayerAuthInputData.VERTICAL_COLLISION))
                .build();

        BedrockMovementState updated = BedrockPacketHorizontalCollisionState.applyToValidationSelectedState(
                state, null, authFrame, null);

        assertEquals(state.physicalFeetPosition(), updated.physicalFeetPosition());
        assertEquals(state.velocity(), updated.velocity());
        assertEquals(0.0D, updated.lastPhysicalDisplacementSquared(), 0.0D);
        assertEquals(Medium.AIR, updated.movementBranch());
        assertEquals(BedrockCollisionFlags.AIR, updated.collisionFlags());
        assertSame(state.inputFrame(), updated.inputFrame());
    }

    @Test
    public void validationSelectedStateKeepsDeterministicSupportedGroundWhenPacketBitMissing() {
        BedrockMovementContext context = airContextWithGround();
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(1.0D, 2.0D, 3.0D),
                new Vec3d(0.1D, 0.0D, 0.2D),
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.ON_GROUND
        );
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID()).build();

        BedrockMovementState updated = BedrockPacketHorizontalCollisionState.applyToValidationSelectedState(
                state,
                context,
                authFrame,
                Vec3d.ZERO);

        assertEquals(Medium.GROUND, updated.movementBranch());
        assertTrue(updated.collisionFlags().onGround());
    }

    @Test
    public void validationSelectedStateKeepsHorizontalCollisionFactWhileMovingUp() {
        BlockCollisionWorld blockWorld = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(1, 2, 2),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(1.3D, 2.0D, 2.6D, 2.3D, 4.0D, 3.4D)))));
        BedrockMovementContext context = new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
        BedrockMovementState state = BedrockMovementState.fromPhysicalFeet(
                new Vec3d(1.0D, 2.0D, 3.0D),
                Vec3d.ZERO,
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.AIR
        );
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID())
                .rawInputFlags(inputFlag(PlayerAuthInputData.HORIZONTAL_COLLISION))
                .build();

        BedrockMovementState updated = BedrockPacketHorizontalCollisionState.applyToValidationSelectedState(
                state,
                context,
                authFrame,
                new Vec3d(0.0D, 0.3D, 0.0D));

        assertTrue(updated.collisionFlags().horizontalCollision());
        assertTrue(updated.collisionFlags().horizontalBlockContact());
        assertTrue(updated.collisionFlags().xCollision());
    }

    @Test
    public void commitVelocityUsesAcceptedFromToDeltaInsteadOfAuthDelta() {
        Vec3d previousPosition = new Vec3d(1.0D, 2.0D, 3.0D);
        Vec3d predictedPosition = new Vec3d(1.1D, 2.0D, 3.1D);
        BedrockMovementState previous = BedrockMovementState.fromPhysicalFeet(
                previousPosition,
                new Vec3d(0.0D, 0.0D, 0.0D),
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.AIR);
        BedrockMovementState predicted = BedrockMovementState.fromPhysicalFeet(
                predictedPosition,
                new Vec3d(0.0D, 0.0D, 0.0D),
                BedrockInputFrame.idle(1L),
                BedrockCollisionFlags.AIR);
        BedrockMovementContext context = airContext();
        BedrockMovementResult movementResult = new BedrockMovementResult(
                previous,
                context,
                context,
                predicted,
                predictedPosition,
                new Vec3d(0.0D, 0.0D, 0.0D),
                false,
                false,
                false,
                false,
                BlockMovementSlowdownState.NONE,
                HoneySlideState.NONE,
                false,
                false,
                0.0D,
                1.0D,
                false,
                false,
                false,
                0.0D);
        BedrockAuthInputFrame authFrame = BedrockAuthInputFrame.builder(UUID.randomUUID())
                .position(new Vec3(1.35D, 2.25D, 2.75D))
                .delta(new Vec3(99.0D, 99.0D, 99.0D))
                .build();
        BedrockValidationSelection selection = new BedrockValidationSelection(
                previousPosition,
                authFrame,
                new Vec3(0.1D, 0.0D, 0.1D),
                predictedPosition,
                authFrame.getPosition());

        BedrockMovementState nextTickBase = BedrockValidationSelectedState.nextTickBase(movementResult, selection);
        Vec3d acceptedDelta = new Vec3d(0.35D, 0.25D, -0.25D);
        BedrockMovementState committed = BedrockSimulation.deriveNextStates(
                movementResult,
                List.of(nextTickBase),
                acceptedDelta).getFirst().state();

        assertEquals(Vec3d.ZERO, nextTickBase.velocity());
        assertEquals(1.35D, nextTickBase.physicalFeetPosition().x(), 1.0E-6D);
        assertEquals(2.25D, nextTickBase.physicalFeetPosition().y(), 0.0D);
        assertEquals(2.75D, nextTickBase.physicalFeetPosition().z(), 0.0D);
        assertEquals(0.35D, committed.velocity().x(), 1.0E-12D);
        assertEquals((float) (((float) 0.25D - (float) 0.08D) * (float) 0.98D), committed.velocity().y(), 0.0D);
        assertEquals(-0.25D, committed.velocity().z(), 0.0D);
    }

    private static long inputFlag(PlayerAuthInputData input) {
        return 1L << input.ordinal();
    }

    private static BedrockMovementContext airContext() {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }

    private static BedrockMovementContext airContextWithGround() {
        BlockCollisionWorld blockWorld = new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 1, 2),
                "minecraft:stone",
                "minecraft:stone",
                List.of(new WorldCollisionBox(0.0D, 1.0D, 2.0D, 2.0D, 2.0D, 4.0D)))));
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, blockWorld),
                EquipmentState.NONE,
                EntityContactState.NONE,
                MovementModifierState.NONE,
                PlayerDimensionsState.DEFAULT);
    }
}
