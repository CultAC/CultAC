package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionCommit;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.PredictionSetbackState;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TeleportData;
import ac.cult.cultac.utils.data.TransactionVel;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BedrockMovementEngineTest {
    @Test
    public void bedrockInputExtentsExpandOnlyHorizontalPreCollisionMovement() {
        var extents = BedrockPredVector.inputExtents(new Vec3d(0.15D, -0.0784D, -0.03D), 0.098D);

        assertEquals(0.052D, extents.minX, 1.0E-12D);
        assertEquals(0.248D, extents.maxX, 1.0E-12D);
        assertEquals(-0.128D, extents.minZ, 1.0E-12D);
        assertEquals(0.068D, extents.maxZ, 1.0E-12D);
        assertEquals(-0.0784D, extents.minY, 1.0E-12D);
        assertEquals(extents.minY, extents.maxY, 0.0D);
    }

    @Test
    public void bedrockUsesModifierPathWithoutJavaFluidHopOrGenericSlimeCandidates() {
        RecordingModifiers modifiers = new RecordingModifiers();

        BedrockMovementEngine.INSTANCE.applyModifiers(
                modifiers,
                null,
                Set.of(Vec3.ZERO),
                null,
                null,
                false);

        assertTrue(modifiers.bedrockModifiersCalled);
        assertFalse(modifiers.defaultModifiersCalled);
    }

    @Test
    public void equivalentBedrockCandidatesRetainPacketModifierProvenance() {
        TransactionVel velocity = new TransactionVel(
                new Vec3(0.0D, -0.0784D, 0.0D), 12, true, false);
        PredVector retained = new PredVector(velocity.getVel());
        PredVector duplicate = new PredVector(velocity.getVel());
        duplicate.addPacketModifier(velocity);

        retained.mergePacketModifierProvenance(duplicate);

        assertTrue(retained.hasPacketModifier(velocity));
        assertEquals(1, retained.packetModifiersLength());
    }

    @Test
    public void setbackCaptureUsesCanonicalCommittedBedrockState() {
        BedrockMovementState canonical = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.15D, -0.0784D, 0.03D),
                BedrockCollisionFlags.ON_GROUND);
        BedrockMovementState alternative = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.15D, 0.1D, 0.03D),
                BedrockCollisionFlags.AIR);
        PredictionCommit commit = commit(canonical, alternative);

        PredictionSetbackState setback = BedrockMovementEngine.INSTANCE.captureSetbackState(commit);

        assertEquals(commit, setback.commit());
        assertEquals(new Vec3(10.0D, 64.0D, 20.0D), setback.position());
        assertEquals(new Vec3(0.15D, -0.0784D, 0.03D), setback.velocity());
        assertTrue(setback.expectedOnGround());
    }

    @Test
    public void relativeDeltaTeleportPreservesEveryCandidateVelocity() {
        BedrockMovementState canonical = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.09D, -0.0784D, 0.03D),
                BedrockCollisionFlags.ON_GROUND);
        BedrockMovementState alternative = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.07D, 0.1D, 0.02D),
                BedrockCollisionFlags.AIR).withGliding(true);
        Vec3 correctedPosition = new Vec3(10.5D, 64.0D, 20.0D);

        TeleportData teleport = new TeleportData(
                correctedPosition,
                new RelativeFlag(RelativeFlag.DELTA_X.getMask()
                        | RelativeFlag.DELTA_Y.getMask()
                        | RelativeFlag.DELTA_Z.getMask()),
                Vec3.ZERO,
                0,
                0);
        PredictionCommit rebased = BedrockMovementEngine.INSTANCE.applyTeleportToCarry(
                commit(canonical, alternative).carry(), teleport);
        List<BedrockProfileState.Entry> entries = ((BedrockNextTickStates) rebased.carry()).profileEntries();

        assertEquals(2, entries.size());
        assertEquals(new Vec3d(10.5D, 64.0D, 20.0D), entries.get(0).state().physicalFeetPosition());
        assertEquals(canonical.velocity(), entries.get(0).state().velocity());
        assertEquals(new Vec3d(10.5D, 64.0D, 20.0D), entries.get(1).state().physicalFeetPosition());
        assertEquals(alternative.velocity(), entries.get(1).state().velocity());
        assertTrue(entries.get(1).state().gliding());
        assertTrue(entries.get(1).state().fallFlyTicks() > 0L);
        assertEquals(Set.of(
                new Vec3(0.09D, -0.0784D, 0.03D),
                new Vec3(0.07D, 0.1D, 0.02D)), rebased.startingVelocities());
    }

    @Test
    public void acknowledgedGlidingMetadataUpdatesTheExistingCarry() {
        BedrockMovementState gliding = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.15D, -0.0784D, 0.03D),
                BedrockCollisionFlags.AIR).withGliding(true);

        var stopped = (BedrockNextTickStates) BedrockMovementEngine.INSTANCE
                .applyAcknowledgedGlidingToCarry(commit(gliding).carry(), false);

        assertFalse(stopped.profileEntries().getFirst().state().gliding());
        assertEquals(gliding.velocity(), stopped.profileEntries().getFirst().state().velocity());
    }

    @Test
    public void teleportRebaseAppliesPacketAuthoredGroundState() {
        BedrockMovementState airborne = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.09D, -0.0784D, 0.03D),
                BedrockCollisionFlags.AIR);
        TeleportData teleport = new TeleportData(
                new Vec3(10.5D, 64.0D, 20.0D),
                new RelativeFlag(RelativeFlag.DELTA_X.getMask()
                        | RelativeFlag.DELTA_Y.getMask()
                        | RelativeFlag.DELTA_Z.getMask()),
                Vec3.ZERO,
                0,
                0);
        teleport.setBedrockOnGround(true);
        PredictionCommit rebased = BedrockMovementEngine.INSTANCE.applyTeleportToCarry(
                commit(airborne).carry(), teleport);
        List<BedrockProfileState.Entry> entries = ((BedrockNextTickStates) rebased.carry()).profileEntries();

        assertEquals(1, entries.size());
        assertTrue(entries.getFirst().state().collisionFlags().onGround());
        assertEquals(airborne.velocity(), entries.getFirst().state().velocity());
    }

    @Test
    public void geyserOnlyTransportKeepsTeleportMotionResetWithoutSetEntityMotion() {
        BedrockMovementState canonical = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.09D, -0.0784D, 0.03D),
                BedrockCollisionFlags.AIR);
        BedrockMovementState alternative = state(
                new Vec3d(10.0D, 64.0D, 20.0D),
                new Vec3d(0.07D, 0.1D, 0.02D),
                BedrockCollisionFlags.AIR);
        TeleportData teleport = new TeleportData(
                new Vec3(10.5D, 64.01D, 20.0D),
                new RelativeFlag(0),
                Vec3.ZERO,
                0,
                0);
        teleport.setBedrockTransportOnly(true);

        PredictionCommit rebased = BedrockMovementEngine.INSTANCE.applyTeleportToCarry(
                commit(canonical, alternative).carry(), teleport);
        List<BedrockProfileState.Entry> entries = ((BedrockNextTickStates) rebased.carry()).profileEntries();

        assertEquals(Vec3d.ZERO, entries.get(0).state().velocity());
        assertEquals(Vec3d.ZERO, entries.get(1).state().velocity());
        assertEquals(Set.of(Vec3.ZERO), rebased.startingVelocities());
    }

    private static PredictionCommit commit(BedrockMovementState... states) {
        List<BedrockProfileState.Entry> entries = java.util.Arrays.stream(states)
                .map(state -> new BedrockProfileState.Entry(state, BedrockMobJumpComponentState.DEFAULT))
                .toList();
        return new PredictionCommit(
                new BedrockNextTickStates(entries),
                BedrockNextTickVelocityDerivation.profileStateVelocities(entries));
    }

    private static BedrockMovementState state(
            Vec3d position,
            Vec3d velocity,
            BedrockCollisionFlags collisionFlags
    ) {
        return BedrockMovementState.fromPhysicalFeet(
                position,
                velocity,
                BedrockInputFrame.idle(10L),
                collisionFlags);
    }

    private static final class RecordingModifiers extends MovementModifiers {
        private boolean bedrockModifiersCalled;
        private boolean defaultModifiersCalled;

        @Override
        public List<PredVector> applyModifers(
                CultPlayer player,
                Set<Vec3> initial,
                SimulationContext state,
                PredictionResult lastResult,
                boolean canTickSkip
        ) {
            defaultModifiersCalled = true;
            return List.of();
        }

        @Override
        public List<PredVector> applyBedrockModifiers(
                CultPlayer player,
                Set<Vec3> initial,
                SimulationContext state,
                PredictionResult lastResult,
                boolean canTickSkip
        ) {
            bedrockModifiersCalled = true;
            return List.of();
        }
    }
}
