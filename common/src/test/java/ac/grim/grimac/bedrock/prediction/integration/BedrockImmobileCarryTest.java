package ac.grim.grimac.bedrock.prediction.integration;

import ac.grim.grimac.bedrock.prediction.geometry.Vec3d;
import ac.grim.grimac.bedrock.prediction.input.BedrockInputFrame;
import ac.grim.grimac.bedrock.prediction.model.AttributeState;
import ac.grim.grimac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.grim.grimac.bedrock.prediction.model.BedrockEffectState;
import ac.grim.grimac.bedrock.prediction.model.EquipmentState;
import ac.grim.grimac.bedrock.prediction.model.Medium;
import ac.grim.grimac.bedrock.prediction.model.MovementModifierState;
import ac.grim.grimac.bedrock.prediction.model.PlayerDimensionsState;
import ac.grim.grimac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.grim.grimac.bedrock.prediction.state.BedrockMovementState;
import ac.grim.grimac.bedrock.prediction.world.BedrockMovementContext;
import ac.grim.grimac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.grim.grimac.bedrock.prediction.world.BlockCollisionWorld;
import ac.grim.grimac.bedrock.prediction.world.EntityContactState;
import ac.grim.grimac.bedrock.prediction.world.FluidState;
import ac.grim.grimac.bedrock.prediction.world.WorldContactState;
import ac.grim.grimac.checks.impl.prediction.PredictionCommit;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class BedrockImmobileCarryTest {
    @Test
    public void immobileTransformAdvancesEveryBoundedCandidateAndPreImmobileState() {
        BedrockMovementState first = state(
                new Vec3d(1.0D, 64.0D, 2.0D),
                new Vec3d(0.1D, -0.08D, 0.02D),
                BedrockCollisionFlags.ON_GROUND);
        BedrockMovementState second = state(
                new Vec3d(1.0D, 64.0D, 2.0D),
                new Vec3d(0.08D, 0.12D, 0.01D),
                new BedrockCollisionFlags(false, true, false, true, false, true, false));
        BedrockMobJumpComponentState reducedSwimImpulse =
                new BedrockMobJumpComponentState(true);
        BedrockProfileState.Entry firstEntry = new BedrockProfileState.Entry(
                first, BedrockMobJumpComponentState.DEFAULT);
        BedrockProfileState.Entry secondEntry = new BedrockProfileState.Entry(
                second, reducedSwimImpulse);
        BedrockInputFrame frame = new BedrockInputFrame(
                12L, 45.0F, 5.0F, false, false, false, Set.of("START_SPRINTING"));

        PredictionCommit transformed = BedrockMovementEngine.INSTANCE.applyImmobileStateToCarry(
                new BedrockNextTickStates(List.of(firstEntry, secondEntry)), frame, false,
                BedrockWorldSnapshot.fromContext(airContext()));
        List<BedrockProfileState.Entry> entries =
                ((BedrockNextTickStates) transformed.carry()).profileEntries();

        assertEquals(2, entries.size());
        assertSame(BedrockMobJumpComponentState.DEFAULT, entries.get(0).mobJumpComponent());
        assertSame(reducedSwimImpulse, entries.get(1).mobJumpComponent());
        assertCandidateTransformed(first, frame, entries.get(0).state());
        assertCandidateTransformed(second, frame, entries.get(1).state());
        for (Vec3 velocity : transformed.startingVelocities()) {
            assertEquals(Vec3.ZERO, velocity);
        }
    }

    @Test
    public void missingCarryStillClearsStartingVelocity() {
        PredictionCommit transformed = BedrockMovementEngine.INSTANCE.applyImmobileStateToCarry(
                null, BedrockInputFrame.idle(1L), false,
                BedrockWorldSnapshot.fromContext(airContext()));

        assertEquals(Set.of(Vec3.ZERO), transformed.startingVelocities());
    }

    private static void assertCandidateTransformed(
            BedrockMovementState source,
            BedrockInputFrame frame,
            BedrockMovementState transformed
    ) {
        assertEquals(source.physicalFeetPosition(), transformed.physicalFeetPosition());
        assertEquals(Vec3d.ZERO, transformed.velocity());
        assertEquals(Vec3d.ZERO, transformed.lastPhysicalDisplacement());
        assertSame(source.collisionFlags(), transformed.collisionFlags());
        assertSame(frame, transformed.inputFrame());
    }

    private static BedrockMovementState state(
            Vec3d position,
            Vec3d velocity,
            BedrockCollisionFlags collisionFlags
    ) {
        return BedrockMovementState.fromPhysicalFeet(
                position,
                velocity,
                BedrockInputFrame.idle(11L),
                collisionFlags);
    }

    private static BedrockMovementContext airContext() {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, BlockCollisionWorld.EMPTY),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(
                        false, false, false, 0.05D, false,
                        false, false, false, 0.35D, 0L),
                PlayerDimensionsState.DEFAULT);
    }
}
