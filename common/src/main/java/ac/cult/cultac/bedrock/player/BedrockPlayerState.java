package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.simulation.frame.BedrockActorDimensions;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockRidingJumpState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientAction;
import ac.cult.cultac.bedrock.protocol.BedrockClientPoseState;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import java.util.Set;
import java.util.UUID;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

/** Prediction and pose state belong to the player's packet executor (the local Geyser tick loop). */
public final class BedrockPlayerState {
    public BedrockLoadingScreenState loadingScreen = new BedrockLoadingScreenState();
    public boolean acknowledgedSprinting;
    public final BedrockBlockBreakActions blockBreakActions = new BedrockBlockBreakActions();
    public final BedrockMovementEffects movementEffects = new BedrockMovementEffects();
    public final BedrockClientActors clientActors = new BedrockClientActors();
    public final BedrockShulkerBoxLids shulkerBoxLids = new BedrockShulkerBoxLids();
    public final BedrockPistonBlocks pistonBlocks = new BedrockPistonBlocks();
    public final ac.cult.cultac.bedrock.prediction.integration.BedrockMovementCorrections movementCorrections =
            new ac.cult.cultac.bedrock.prediction.integration.BedrockMovementCorrections();
    /** The session's view of its world registries through Geyser. */
    public final ac.cult.cultac.bedrock.bridge.GeyserWorldView worldView =
            new ac.cult.cultac.bedrock.bridge.GeyserWorldView();
    // Accessed under the CultPlayer transaction lock.
    public ac.cult.cultac.player.CultPlayer.BedrockTransaction lastClientboundTransaction;
    // The vehicle the client predicted on its last tick; null after a tick it predicted none.
    public ac.cult.cultac.utils.data.packetentity.PacketEntity predictedVehicle;
    private final UUID playerUuid;

    private BedrockProtocolVersion protocolVersion = BedrockProtocolVersion.UNKNOWN;
    private boolean setbacksEnabled = true;
    private BedrockAuthInputFrame lastOfferedFrame;
    private BedrockAuthInputFrame lastProcessedFrame;
    private long processedAuthInputSequence;
    private long lastMovementTick = -1;

    /** The client state this player's block collision shapes read, in the coordinate frame of its latest input. */
    public ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext blockShapeContext(
            ac.cult.cultac.player.CultPlayer player) {
        return new ac.cult.cultac.bedrock.prediction.api.BedrockBlockShapeContext(
                position -> shulkerBoxLids.lift(
                        ac.cult.cultac.protocol.value.BlockPos.asLong(position.x(), position.y(), position.z())),
                position -> ac.cult.cultac.utils.collisions.BedrockMovingBlockShape.boxes(player, position),
                position -> pistonBlocks.progress(
                        new ac.cult.cultac.protocol.value.BlockPos(position.x(), position.y(), position.z()),
                        player.compensatedWorld::getBlockStateIdAt),
                lastOfferedFrame == null
                        ? ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame.IDENTITY
                        : lastOfferedFrame.getCoordinateFrame());
    }

    public long lastMovementTick() {
        return lastMovementTick;
    }

    public boolean acceptMovementTick(long tick) {
        if (tick <= lastMovementTick) return false;
        lastMovementTick = tick;
        return true;
    }

    private BedrockMoveFrame lastMoveFrame;
    private String lastAuthInputMatchStatus = "no auth input processed";
    private BedrockAuthInputFrame lastPoseAppliedFrame;
    private BedrockClientPoseState lastPoseAppliedState;
    private BedrockClientPoseState trackedPoseState = BedrockClientPoseState.STANDING;
    private boolean swimmingRequested;
    private final BedrockFrameActions actions = new BedrockFrameActions();
    private long lastVerboseMovementLog = Long.MIN_VALUE;
    private PlayerDimensionsState confirmedBoundingBoxSize;
    private PlayerDimensionsState collisionDefinition;
    private Long observedActorRuntimeId;
    private BedrockRidingJumpState ridingJump = BedrockRidingJumpState.INITIAL;
    private BedrockAuthInputFrame ridingJumpFrame;
    private int horseJumpRelease = -1;

    public BedrockPlayerState(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    /** Null for older captures whose actor-creation history is unavailable. */
    public Long observedActorRuntimeId() {
        return observedActorRuntimeId;
    }

    public void recordActorCreation(long runtimeEntityId) {
        collisionDefinition = null;
        movementCorrections.clear();
        predictedVehicle = null;
        lastMovementTick = -1;
        movementEffects.clear();
        clientActors.clear();
        observedActorRuntimeId = runtimeEntityId;
        ridingJump = BedrockRidingJumpState.INITIAL;
        ridingJumpFrame = null;
        horseJumpRelease = -1;
    }

    public void tickRidingJump(BedrockAuthInputFrame frame, boolean canPowerJump) {
        if (ridingJumpFrame == frame) return;
        ridingJumpFrame = frame;
        var step = ridingJump.tick(frame.isJumpCurrentRaw(), canPowerJump);
        ridingJump = step.state();
        horseJumpRelease = step.release();
    }

    public int horseJumpRelease(BedrockAuthInputFrame frame) {
        return ridingJumpFrame == frame ? horseJumpRelease : -1;
    }

    public void offerAuthInputFrame(BedrockAuthInputFrame frame) {
        if (!playerUuid.equals(frame.getPlayerUuid())) {
            throw new IllegalArgumentException("Auth input frame UUID does not match this state");
        }

        if (frame.getProtocolVersion().isKnown()) {
            protocolVersion = frame.getProtocolVersion();
        }
        lastOfferedFrame = frame;
        lastAuthInputMatchStatus = "observed auth input tick=" + frame.getClientTick();
        actions.forFrame(frame);
        applyClientPoseFrame(frame);
    }

    public long recordProcessedAuthInputFrame(BedrockAuthInputFrame frame, BedrockPredictionTrigger trigger) {
        if (frame == null) {
            return processedAuthInputSequence;
        }
        if (!playerUuid.equals(frame.getPlayerUuid())) {
            throw new IllegalArgumentException("Auth input frame UUID does not match this state");
        }
        if (frame == lastProcessedFrame) {
            return processedAuthInputSequence;
        }
        lastProcessedFrame = frame;
        processedAuthInputSequence++;
        if (frame.isSwimming()) {
            swimmingRequested = true;
        }
        if (frame.isStopSwimming()) {
            swimmingRequested = false;
        }
        lastAuthInputMatchStatus = trigger == null
                ? "processed auth input tick=" + frame.getClientTick() + " sequence=" + processedAuthInputSequence
                : "processed auth input tick=" + frame.getClientTick() + " via " + trigger + " sequence="
                        + processedAuthInputSequence;
        actions.forFrame(frame);
        applyClientPoseFrame(frame);
        return processedAuthInputSequence;
    }

    /** Prediction runs synchronously after recordProcessedAuthInputFrame; raw client ticks cannot advance it. */
    public long authoritativeInputTick() {
        return processedAuthInputSequence;
    }

    /** Wire timestamp paired with the latest processed input, not the simulation sequence. */
    public long processedClientTick() {
        return lastProcessedFrame == null ? 0L : lastProcessedFrame.getClientTick();
    }

    /** Clears packet-local input while retaining confirmed pose and dimensions. */
    public void clearTransientMovementInputState() {
        lastOfferedFrame = null;
        lastProcessedFrame = null;
        lastMoveFrame = null;
        lastPoseAppliedFrame = null;
        lastPoseAppliedState = null;
        actions.clear();
        lastAuthInputMatchStatus = "transient auth input state cleared";
    }

    public void applyAcknowledgedBoundingBoxMetadata(Float width, Float height) {
        if (width == null && height == null
                || width != null && (!Float.isFinite(width) || width <= 0.0F)
                || height != null && (!Float.isFinite(height) || height <= 0.0F)) {
            return;
        }
        PlayerDimensionsState previous = confirmedBoundingBoxSize;
        if (previous == null) {
            previous = PlayerDimensionsState.DEFAULT;
        }
        confirmedBoundingBoxSize = new PlayerDimensionsState(
                width == null ? previous.width() : width, height == null ? previous.height() : height);
    }

    public void applyAcknowledgedPoseMetadata(Boolean crawling, Boolean swimming) {
        applyAcknowledgedPoseMetadata(crawling, swimming, null, null, null);
    }

    public void applyAcknowledgedPoseMetadata(
            Boolean crawling, Boolean swimming, Boolean sneaking, Boolean spinning, Boolean sleeping) {
        applyAcknowledgedPoseMetadata(crawling, swimming, sneaking, spinning, sleeping, null);
    }

    public void applyAcknowledgedPoseMetadata(
            Boolean crawling, Boolean swimming, Boolean sneaking, Boolean spinning, Boolean sleeping, Boolean gliding) {
        if (crawling != null) {
            trackedPoseState = trackedPoseState.withCrawling(crawling);
        }
        if (swimming != null) {
            trackedPoseState = trackedPoseState.withSwimming(swimming);
            if (!swimming) {
                swimmingRequested = false;
            }
        }
        if (sneaking != null) trackedPoseState = trackedPoseState.withSneaking(sneaking);
        if (spinning != null) trackedPoseState = trackedPoseState.withSpinning(spinning);
        if (sleeping != null) trackedPoseState = trackedPoseState.withSleeping(sleeping);
        if (gliding != null) trackedPoseState = trackedPoseState.withGliding(gliding);
    }

    public boolean isSwimmingRequested() {
        return swimmingRequested;
    }

    /**
     * The collision definition the client holds at {@code scale}: it multiplies the definition it was given by the
     * scale in its own entity data. Null until the definition is confirmed.
     */
    public PlayerDimensionsState scaledCollisionDefinition(float scale) {
        return collisionDefinition == null
                ? null
                : BedrockActorDimensions.scaledDefinition(collisionDefinition, scale);
    }

    /** {@code scale} is the player's scale, which for a Bedrock player is the one its client acknowledged. */
    public BedrockMovementState applyConfirmedBoundingBoxSize(
            BedrockMovementState source, BedrockInputFrame currentFrame, float scale) {
        if (source != null && !source.isVehicle() && collisionDefinition != null) {
            source = BedrockActorDimensions.withCollisionDefinition(source, scaledCollisionDefinition(scale));
        }
        if (source == null
                || confirmedBoundingBoxSize == null
                || confirmedBoundingBoxSize.equals(source.acknowledgedPlayerDimensions())) {
            return source;
        }
        BedrockBoundingBoxMode currentMode = BedrockBoundingBoxMode.resolve(source, currentFrame);
        return source.withPlayerDimensions(currentMode, confirmedBoundingBoxSize, true);
    }

    public String boundingBoxStatus() {
        return "confirmedSize=" + confirmedBoundingBoxSize + " collisionDefinition=" + collisionDefinition;
    }

    public void confirmCollisionDefinition(PlayerDimensionsState definition) {
        collisionDefinition = definition;
    }

    public void recordClientAction(BedrockClientAction action) {
        actions.record(action);
    }

    public void applyAcknowledgedItemUseMetadata(Boolean usingItem) {
        actions.applyAcknowledgedItemUse(usingItem);
    }

    public Set<String> actionInputFor(BedrockAuthInputFrame frame) {
        return actions.forFrame(frame);
    }

    private boolean isLastPoseFrame(BedrockAuthInputFrame frame) {
        if (frame == null || lastPoseAppliedFrame == null || lastPoseAppliedState == null) {
            return false;
        }
        return frame.samePacketAs(lastPoseAppliedFrame);
    }

    private void applyClientPoseFrame(BedrockAuthInputFrame frame) {
        if (frame == null || isLastPoseFrame(frame)) {
            return;
        }
        lastPoseAppliedFrame = frame;
        lastPoseAppliedState = trackedPoseState;
        trackedPoseState = PoseTransition.applyAll(trackedPoseState, frame);
        Set<String> frameActions = actions.forFrame(frame);
        if (frameActions.contains("START_SNEAKING") || frame.isStartSneaking()) {
            trackedPoseState = trackedPoseState.withSneaking(true);
        }
        if (frameActions.contains("STOP_SNEAKING") || frame.isStopSneaking()) {
            trackedPoseState = trackedPoseState.withSneaking(false);
        }
        if (frameActions.contains("START_CRAWLING")) trackedPoseState = trackedPoseState.withCrawling(true);
        if (frameActions.contains("STOP_CRAWLING") || frame.isStopCrawling()) {
            trackedPoseState = trackedPoseState.withCrawling(false);
        }
        if (frameActions.contains("START_SWIMMING")) trackedPoseState = trackedPoseState.withSwimming(true);
        if (frameActions.contains("STOP_SWIMMING") || frame.isStopSwimming()) {
            trackedPoseState = trackedPoseState.withSwimming(false);
        }
    }

    public BedrockClientPoseState getClientPoseState(BedrockAuthInputFrame frame) {
        if (frame == null) {
            return trackedPoseState;
        }
        return isLastPoseFrame(frame) ? lastPoseAppliedState : trackedPoseState;
    }

    private enum PoseTransition {
        SNEAKING {
            @Override
            BedrockClientPoseState apply(BedrockClientPoseState state, BedrockAuthInputFrame frame) {
                if (frame.isStopSneaking() || frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_RELEASED_RAW)) {
                    return state.withSneaking(false);
                }
                return frame.hasRawInputFlag(PlayerAuthInputData.SNEAKING) ? state.withSneaking(true) : state;
            }
        },
        CRAWLING {
            @Override
            BedrockClientPoseState apply(BedrockClientPoseState state, BedrockAuthInputFrame frame) {
                if (frame.isStopCrawling()) {
                    return state.withCrawling(false);
                }
                return frame.isStartCrawling() ? state.withCrawling(true) : state;
            }
        },
        SWIMMING {
            @Override
            BedrockClientPoseState apply(BedrockClientPoseState state, BedrockAuthInputFrame frame) {
                if (frame.isStopSwimming()) {
                    return state.withSwimming(false);
                }
                return frame.isSwimming() ? state.withSwimming(true) : state;
            }
        };

        abstract BedrockClientPoseState apply(BedrockClientPoseState state, BedrockAuthInputFrame frame);

        static BedrockClientPoseState applyAll(BedrockClientPoseState state, BedrockAuthInputFrame frame) {
            if (frame == null) {
                return state;
            }
            BedrockClientPoseState next = state;
            for (PoseTransition transition : values()) {
                next = transition.apply(next, frame);
            }
            return next;
        }
    }

    public boolean consumeVerboseMovementLogCooldown(double cooldownSeconds) {
        cooldownSeconds = Math.max(0.0D, cooldownSeconds);
        if (cooldownSeconds == 0.0D) {
            return true;
        }

        long now = System.nanoTime();
        long cooldownNanos = (long) (cooldownSeconds * 1_000_000_000.0D);
        if (lastVerboseMovementLog != Long.MIN_VALUE && now - lastVerboseMovementLog < cooldownNanos) {
            return false;
        }

        lastVerboseMovementLog = now;
        return true;
    }

    public BedrockProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public void setSetbacksEnabled(boolean setbacksEnabled) {
        this.setbacksEnabled = setbacksEnabled;
    }

    public boolean shouldEnforceSetbacks() {
        return setbacksEnabled;
    }

    public BedrockAuthInputFrame getLastOfferedFrame() {
        return lastOfferedFrame;
    }

    public String getLastAuthInputMatchStatus() {
        return lastAuthInputMatchStatus;
    }

    public BedrockMoveFrame getLastMoveFrame() {
        return lastMoveFrame;
    }

    public void setLastMoveFrame(BedrockMoveFrame lastMoveFrame) {
        this.lastMoveFrame = lastMoveFrame;
        if (lastMoveFrame != null
                && lastMoveFrame.protocolVersion() != null
                && lastMoveFrame.protocolVersion().isKnown()) {
            protocolVersion = lastMoveFrame.protocolVersion();
        }
    }
}
