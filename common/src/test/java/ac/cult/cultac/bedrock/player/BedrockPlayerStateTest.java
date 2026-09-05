package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockCollisionFlags;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class BedrockPlayerStateTest {
    private static final UUID PLAYER_UUID = UUID.fromString("7cc5c1f7-4b66-4c7c-bbb6-b1b09478015d");

    @Test
    public void setbacksAreEnabledByDefault() {
        assertTrue(new BedrockPlayerState(PLAYER_UUID).shouldEnforceSetbacks());
    }

    @Test
    public void setbacksCanBeDisabledWithoutDisablingValidationState() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);

        state.setSetbacksEnabled(false);

        assertFalse(state.shouldEnforceSetbacks());
    }

    @Test
    public void authInputFrameUpdatesLastObservedState() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame first = frame(1L, 1.0D);
        BedrockAuthInputFrame second = frame(2L, 2.0D);

        state.offerAuthInputFrame(first);
        state.offerAuthInputFrame(second);

        assertSame(second, state.getLastOfferedFrame());
    }

    @Test
    public void processedAuthInputStatusUsesPluginTrigger() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame frame = frame(10L, 10.0D);

        state.offerAuthInputFrame(frame);
        state.recordProcessedAuthInputFrame(frame, BedrockPredictionTrigger.AUTH_INPUT_PLUGIN_MESSAGE);

        assertTrue(state.getLastAuthInputMatchStatus().contains("processed auth input tick=10 via AUTH_INPUT_PLUGIN_MESSAGE"));
    }

    @Test
    public void oneShotActionsConsumeOnce() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame frame = frame(20L, 20.0D);

        state.recordItemReleaseAction();
        state.recordStartSpinAttackAction();
        state.recordStopSpinAttackAction();

        assertTrue(state.consumeItemReleaseFor(frame));
        assertFalse(state.consumeItemReleaseFor(frame));
        assertTrue(state.consumeStartSpinAttackFor(frame));
        assertFalse(state.consumeStartSpinAttackFor(frame));
        assertTrue(state.consumeStopSpinAttackFor(frame));
        assertFalse(state.consumeStopSpinAttackFor(frame));
    }

    @Test
    public void riptideChargeStartsOnTheObservedUseTickOnlyOnce() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame use = frameBuilder(20L, 20.0D)
                .usingItem(true)
                .build();
        state.offerAuthInputFrame(use);

        assertTrue(state.shouldStartRiptideCharge(use, true));
        assertFalse(state.shouldStartRiptideCharge(use, true));

        state.clearRiptideUseTracking();
        assertTrue(state.shouldStartRiptideCharge(use, true));
    }

    @Test
    public void transientTeleportResetPreservesPoseAndConfirmedDimensions() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame swimming = frameBuilder(1L, 0.0D)
                .startSwimming(true)
                .build();
        state.offerAuthInputFrame(swimming);
        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 0.6F);
        state.recordItemReleaseAction();

        state.clearTransientMovementInputState();

        BedrockAuthInputFrame next = frame(2L, 0.0D);
        state.offerAuthInputFrame(next);
        assertTrue(state.getClientPoseState(next).swimming());
        BedrockMovementState resized = state.applyConfirmedBoundingBoxSize(
                movementState(1.8D, false), BedrockInputFrame.idle(2L));
        assertEquals((double) 0.6F, resized.playerDimensions().height(), 0.0D);
        assertFalse(state.consumeItemReleaseFor(next));
    }

    @Test
    public void fullRespawnResetStillClearsPoseAndConfirmedDimensions() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame swimming = frameBuilder(1L, 0.0D)
                .startSwimming(true)
                .build();
        state.offerAuthInputFrame(swimming);
        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 0.6F);

        state.clearMovementInputState();

        BedrockAuthInputFrame next = frame(2L, 0.0D);
        state.offerAuthInputFrame(next);
        assertFalse(state.getClientPoseState(next).swimming());
        BedrockMovementState original = movementState(1.8D, false);
        assertSame(original, state.applyConfirmedBoundingBoxSize(original, BedrockInputFrame.idle(2L)));
    }

    @Test
    public void acknowledgedBoundingBoxSizeReplacesPreDeliveryState() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.5F);

        BedrockMovementState source = movementState(1.49D, false);
        BedrockMovementState acknowledged = state.applyConfirmedBoundingBoxSize(source, source.inputFrame());
        assertEquals(1.5D, acknowledged.playerDimensions().height(), 0.0D);
        assertTrue(acknowledged.explicitPlayerDimensions());
    }

    @Test
    public void acknowledgedSizeIsCommittedAtCurrentInputMode() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.5F);
        BedrockMovementState standing = movementState(1.8D, false);
        BedrockInputFrame sneaking = new BedrockInputFrame(1L, 0.0F, 0.0F, false, true, false);

        BedrockMovementState acknowledged = state.applyConfirmedBoundingBoxSize(standing, sneaking);

        assertEquals(BedrockBoundingBoxMode.SNEAKING, acknowledged.boundingBoxMode());
        assertEquals(1.5D, acknowledged.playerDimensions().height(), 0.0D);
        assertEquals(1.5D, BedrockActorDimensions.resolve(
                acknowledged, acknowledged.playerDimensions(), sneaking).dimensions().height(), 0.0D);

        BedrockInputFrame standingAgain = BedrockInputFrame.idle(2L);
        assertEquals(1.5D, BedrockActorDimensions.resolve(
                acknowledged, acknowledged.playerDimensions(), standingAgain).dimensions().height(), 0.0D);
    }

    @Test
    public void rapidBoundingBoxCallbacksApplyInResponseOrder() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockMovementState movement = movementState(1.49D, false);

        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.5F);
        BedrockMovementState afterFirstAck = state.applyConfirmedBoundingBoxSize(movement, movement.inputFrame());
        assertEquals(1.5D, afterFirstAck.playerDimensions().height(), 0.0D);
        assertTrue(afterFirstAck.explicitPlayerDimensions());

        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.8F);
        BedrockMovementState afterSecondAck = state.applyConfirmedBoundingBoxSize(afterFirstAck, afterFirstAck.inputFrame());
        assertEquals((double) 1.8F, afterSecondAck.playerDimensions().height(), 0.0D);
        assertTrue(afterSecondAck.explicitPlayerDimensions());

        state.applyAcknowledgedBoundingBoxMetadata(0.6F, 1.5F);
        BedrockMovementState afterThirdAck = state.applyConfirmedBoundingBoxSize(afterSecondAck, afterSecondAck.inputFrame());
        assertEquals(1.5D, afterThirdAck.playerDimensions().height(), 0.0D);
        assertTrue(afterThirdAck.explicitPlayerDimensions());
    }

    @Test
    public void acknowledgedBoundingBoxUpdateCommitsWidthAndHeight() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        state.applyAcknowledgedBoundingBoxMetadata(0.2F, 0.2F);

        BedrockMovementState source = movementState(1.8D, false);
        BedrockMovementState collapsed = state.applyConfirmedBoundingBoxSize(source, source.inputFrame());

        assertEquals((double) 0.2F, collapsed.playerDimensions().width(), 0.0D);
        assertEquals((double) 0.2F, collapsed.playerDimensions().height(), 0.0D);
    }

    @Test
    public void partialBoundingBoxUpdatesAreReconstructedInLatencyOrder() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        state.applyAcknowledgedBoundingBoxMetadata(null, 1.5F);
        BedrockMovementState source = movementState(1.8D, false);
        BedrockMovementState heightOnly = state.applyConfirmedBoundingBoxSize(
                source, source.inputFrame());
        assertEquals(PlayerDimensionsState.DEFAULT_WIDTH,
                heightOnly.playerDimensions().width(), 0.0D);
        assertEquals((double) 1.5F, heightOnly.playerDimensions().height(), 0.0D);

        state.applyAcknowledgedBoundingBoxMetadata(0.2F, null);
        BedrockMovementState complete = state.applyConfirmedBoundingBoxSize(
                heightOnly, heightOnly.inputFrame());
        assertEquals((double) 0.2F, complete.playerDimensions().width(), 0.0D);
        assertEquals((double) 1.5F, complete.playerDimensions().height(), 0.0D);
    }

    @Test
    public void rawSneakButtonDoesNotRestoreStoppedActorPose() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame sneaking = withInputFlags(
                frameBuilder(1L, 0.0D), PlayerAuthInputData.SNEAKING,
                PlayerAuthInputData.SNEAK_CURRENT_RAW).build();
        BedrockAuthInputFrame stoppedWithButtonHeld = withInputFlags(
                frameBuilder(2L, 0.0D), PlayerAuthInputData.STOP_SNEAKING,
                PlayerAuthInputData.SNEAK_CURRENT_RAW).stopSneaking(true).build();
        BedrockAuthInputFrame buttonStillHeld = withInputFlags(
                frameBuilder(3L, 0.0D), PlayerAuthInputData.SNEAK_CURRENT_RAW).build();

        state.offerAuthInputFrame(sneaking);
        assertTrue(state.getClientPoseState(sneaking).sneaking());
        state.offerAuthInputFrame(stoppedWithButtonHeld);
        assertFalse(state.getClientPoseState(stoppedWithButtonHeld).sneaking());
        state.offerAuthInputFrame(buttonStillHeld);
        assertFalse(state.getClientPoseState(buttonStillHeld).sneaking());
    }

    @Test
    public void acknowledgedPoseMetadataReplacesStaleClientPose() {
        BedrockPlayerState state = new BedrockPlayerState(PLAYER_UUID);
        BedrockAuthInputFrame crawling = frameBuilder(1L, 0.0D)
                .startCrawling(true)
                .build();
        state.offerAuthInputFrame(crawling);
        BedrockAuthInputFrame carried = frame(2L, 0.0D);
        state.offerAuthInputFrame(carried);
        assertTrue(state.getClientPoseState(carried).crawling());

        state.applyAcknowledgedPoseMetadata(false, false);

        BedrockAuthInputFrame next = frame(3L, 0.0D);
        state.offerAuthInputFrame(next);
        assertFalse(state.getClientPoseState(next).crawling());
        assertFalse(state.getClientPoseState(next).swimming());
    }

    private static BedrockMovementState movementState(double height, boolean explicit) {
        return BedrockMovementState.fromPhysicalFeet(
                new Vec3d(0.0D, 64.0D, 0.0D),
                Vec3d.ZERO,
                BedrockInputFrame.idle(0L),
                BedrockCollisionFlags.AIR)
                .withPlayerDimensions(new PlayerDimensionsState(0.6D, height), explicit);
    }

    private static BedrockAuthInputFrame frame(long tick, double x) {
        return frameBuilder(tick, x)
                .build();
    }

    private static BedrockAuthInputFrame.Builder frameBuilder(long tick, double x) {
        return BedrockAuthInputFrame.builder(PLAYER_UUID)
                .clientTick(tick)
                .position(new Vec3(x, 64.0D, 0.0D))
                .rotation(90.0F, 10.0F, 90.0F)
                .moveVector(0.0F, 0.0F)
                .authorityMode("client");
    }

    private static BedrockAuthInputFrame.Builder withInputFlags(
            BedrockAuthInputFrame.Builder builder,
            PlayerAuthInputData... flags
    ) {
        long low = 0L;
        long high = 0L;
        for (PlayerAuthInputData flag : flags) {
            if (flag.ordinal() < Long.SIZE) {
                low |= 1L << flag.ordinal();
            } else {
                high |= 1L << (flag.ordinal() - Long.SIZE);
            }
        }
        return builder.rawInputFlags(low).rawInputFlagsHigh(high);
    }
}
