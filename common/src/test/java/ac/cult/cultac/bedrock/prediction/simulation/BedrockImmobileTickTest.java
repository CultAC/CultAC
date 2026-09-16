package ac.cult.cultac.bedrock.prediction.simulation;

import ac.cult.cultac.bedrock.prediction.geometry.BlockPosition;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.input.BedrockPoseInputData;
import ac.cult.cultac.bedrock.prediction.model.AttributeState;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockEffectState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.model.EquipmentState;
import ac.cult.cultac.bedrock.prediction.model.Medium;
import ac.cult.cultac.bedrock.prediction.model.MovementModifierState;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockMobJumpComponentState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockDolphinBoost;
import ac.cult.cultac.bedrock.prediction.world.BedrockClimbableContact;
import ac.cult.cultac.bedrock.prediction.world.BedrockMovementContext;
import ac.cult.cultac.bedrock.prediction.world.BedrockWorldSnapshot;
import ac.cult.cultac.bedrock.prediction.world.BlockCollisionWorld;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.prediction.world.FluidState;
import ac.cult.cultac.bedrock.prediction.world.PlacedBlockCollision;
import ac.cult.cultac.bedrock.prediction.world.WorldContactState;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class BedrockImmobileTickTest {
    @Test
    public void glideActionsAdvanceFallFlyBeforeVelocityIsZeroed() {
        BedrockMovementState current = state(false, 0L, false, 0L, false, 0L);

        BedrockImmobileTick.Result started = advance(
                current, frame(BedrockPoseInputData.START_GLIDING_ACTION), context(false, 0L));

        assertTrue(started.state().gliding());
        assertTrue(started.state().glidingRequest());
        assertEquals(1L, started.state().fallFlyTicks());
        assertEquals(Vec3d.ZERO, started.state().velocity());

        BedrockImmobileTick.Result stopped = advance(
                started.state(), frame(BedrockPoseInputData.STOP_GLIDING_ACTION), context(false, 0L));
        assertFalse(stopped.state().gliding());
        assertFalse(stopped.state().glidingRequest());
        assertEquals(0L, stopped.state().fallFlyTicks());
    }

    @Test
    public void ongoingGlideIncrementsFallFlyOnEveryImmobileTick() {
        BedrockMovementState current = state(true, 5L, false, 0L, false, 0L);

        BedrockMovementState next = advance(current, frame(), context(false, 0L)).state();

        assertTrue(next.gliding());
        assertEquals(6L, next.fallFlyTicks());
    }

    @Test
    public void spinAttackUsesNormalLifetimeAndExpiry() {
        BedrockMovementState current = state(false, 0L, true, 18L, false, 0L);

        BedrockMovementState nineteenth = advance(current, frame(), context(false, 0L)).state();
        assertTrue(nineteenth.riptideSpinActive());
        assertEquals(19L, nineteenth.riptideSpinTicks());

        BedrockMovementState expired = advance(nineteenth, frame(), context(false, 0L)).state();
        assertFalse(expired.riptideSpinActive());
        assertEquals(0L, expired.riptideSpinTicks());
    }

    @Test
    public void itemUseBookkeepingAdvancesReleasesAndExpires() {
        BedrockMovementContext itemUseContext = context(true, 9L);
        BedrockMovementState current = state(false, 0L, false, 0L, true, 8L);

        BedrockMovementState ninth = advance(current, frame(), itemUseContext).state();
        assertTrue(ninth.itemUseSlowdownActive());
        assertEquals(9L, ninth.itemUseSlowdownTicks());

        BedrockMovementState expired = advance(ninth, frame(), itemUseContext).state();
        assertFalse(expired.itemUseSlowdownActive());
        assertEquals(0L, expired.itemUseSlowdownTicks());

        BedrockMovementState released = advance(
                current, frame("RELEASE_USING_ITEM"), itemUseContext).state();
        assertFalse(released.itemUseSlowdownActive());
        assertEquals(0L, released.itemUseSlowdownTicks());
    }

    @Test
    public void exactPowderContactAdvancesSyntheticCounterAndResetsFallDistance() {
        BlockCollisionWorld powderWorld = powderSnowWorld();
        BedrockMovementContext powderContext = context(false, 0L, powderWorld);
        BedrockMovementState current = state(false, 0L, false, 0L, false, 0L);

        BedrockMovementState next = advance(current, frame(), powderContext).state();

        assertEquals(current.powderSnowTicks() + 1L, next.powderSnowTicks());
        assertEquals(BlockMovementSlowdownState.POWDER_SNOW,
                next.pendingBlockMovementSlowdownState());
        assertEquals(0.0F, next.fallDistance(), 0.0F);
    }

    @Test
    public void idleImmobileTickPreservesPendingMobJumpComponent() {
        BedrockMobJumpComponentState reduced = new BedrockMobJumpComponentState(true);

        BedrockImmobileTick.Result result = BedrockImmobileTick.advance(
                state(false, 0L, false, 0L, false, 0L),
                frame(),
                BedrockWorldSnapshot.fromContext(context(false, 0L)),
                reduced,
                false);

        // MobJumpSystem is filtered by MobIsJumpingFlagComponent. With no jump
        // input, vanilla does not consume the pending reduced swim-up impulse.
        assertSame(reduced, result.mobJumpComponent());
    }

    private static BedrockImmobileTick.Result advance(
            BedrockMovementState current,
            BedrockInputFrame frame,
            BedrockMovementContext context
    ) {
        return BedrockImmobileTick.advance(
                current,
                frame,
                BedrockWorldSnapshot.fromContext(context),
                BedrockMobJumpComponentState.DEFAULT,
                false);
    }

    private static BedrockInputFrame frame(String... inputData) {
        return new BedrockInputFrame(
                42L, 0.0F, 0.0F, false, false, false, Set.of(inputData));
    }

    private static BedrockMovementState state(
            boolean gliding,
            long fallFlyTicks,
            boolean spinActive,
            long spinTicks,
            boolean itemUseActive,
            long itemUseTicks
    ) {
        return new BedrockMovementState(
                new BedrockMovementState.Motion(
                        Vec3d.ZERO,
                        new Vec3d(0.2D, -0.1D, 0.05D),
                        0.0525D,
                        new Vec3d(0.2D, -0.1D, 0.05D),
                        BedrockInputFrame.idle(41L),
                        BedrockCollisionFlags.AIR),
                new BedrockMovementState.ActorState(
                        new BedrockMovementState.ContactState(
                                BlockMovementSlowdownState.NONE,
                                BedrockClimbableContact.NONE,
                                false),
                        new BedrockMovementState.PoseState(
                                false, false, false, itemUseActive),
                        new BedrockMovementState.TravelMode(
                                gliding, gliding, false, false, Medium.AIR),
                        BedrockBoundingBoxMode.DEFAULT,
                        PlayerDimensionsState.DEFAULT,
                        null),
                new BedrockMovementState.TickMemory(
                        11L,
                        3L,
                        fallFlyTicks,
                        6.0F,
                        0.0D,
                        0L,
                        spinActive,
                        spinTicks,
                        0L,
                        itemUseTicks,
                        BedrockDolphinBoost.INITIAL));
    }

    private static BedrockMovementContext context(
            boolean itemUseActive,
            long itemUseDurationTicks
    ) {
        return context(itemUseActive, itemUseDurationTicks, BlockCollisionWorld.EMPTY);
    }

    private static BedrockMovementContext context(
            boolean itemUseActive,
            long itemUseDurationTicks,
            BlockCollisionWorld world
    ) {
        return new BedrockMovementContext(
                BedrockEffectState.NONE,
                AttributeState.DEFAULT,
                new WorldContactState(Medium.AIR, FluidState.NONE, world),
                EquipmentState.NONE,
                EntityContactState.NONE,
                new MovementModifierState(
                        true,
                        false,
                        false,
                        false,
                        0.05D,
                        false,
                        false,
                        false,
                        itemUseActive,
                        0.35D,
                        itemUseDurationTicks),
                PlayerDimensionsState.DEFAULT);
    }

    private static BlockCollisionWorld powderSnowWorld() {
        return new BlockCollisionWorld(List.of(PlacedBlockCollision.manual(
                new BlockPosition(0, 0, 0),
                "minecraft:powder_snow",
                "minecraft:powder_snow",
                List.of(),
                Set.of(PlacedBlockCollision.BlockContactBehavior.POWDER_SNOW))));
    }
}
