package ac.cult.cultac.bedrock.player;

import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.input.BedrockInputFrame;
import ac.cult.cultac.bedrock.prediction.model.BedrockBoundingBoxMode;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockClientPoseState;
import ac.cult.cultac.bedrock.protocol.BedrockMoveFrame;
import ac.cult.cultac.bedrock.protocol.BedrockProtocolVersion;
import java.util.UUID;
import java.util.WeakHashMap;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockPlayerState {
    // Accessed under the CultPlayer transaction lock.
    public ac.cult.cultac.player.CultPlayer.BedrockTransaction lastClientboundTransaction;
    private final UUID playerUuid;

    private BedrockProtocolVersion protocolVersion = BedrockProtocolVersion.UNKNOWN;
    private boolean setbacksEnabled = true;
    private BedrockAuthInputFrame lastOfferedFrame;
    private long offeredAuthInputSequence;
    private BedrockAuthInputFrame lastProcessedFrame;
    private long processedAuthInputSequence;
    private final WeakHashMap<BedrockAuthInputFrame, Long> authoritativeInputSequences = new WeakHashMap<>();
    private BedrockMoveFrame lastMoveFrame;
    private String lastAuthInputMatchStatus = "no auth input processed";
    private BedrockAuthInputFrame lastPoseAppliedFrame;
    private BedrockClientPoseState lastPoseAppliedState;
    private long trackedRiptideUseStartInputTick = Long.MIN_VALUE;
    private BedrockClientPoseState trackedPoseState = BedrockClientPoseState.STANDING;
    private boolean pendingStartGlidingAction;
    private boolean pendingStopGlidingAction;
    private boolean pendingItemRelease;
    private boolean pendingStartSpinAttack;
    private boolean pendingStopSpinAttack;
    private long lastVerboseMovementLog = Long.MIN_VALUE;
    private PlayerDimensionsState confirmedBoundingBoxSize;

    public BedrockPlayerState(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public void offerAuthInputFrame(BedrockAuthInputFrame frame) {
        if (!playerUuid.equals(frame.getPlayerUuid())) {
            throw new IllegalArgumentException("Auth input frame UUID does not match this state");
        }

        if (frame.getProtocolVersion().isKnown()) {
            protocolVersion = frame.getProtocolVersion();
        }
        lastOfferedFrame = frame;
        offeredAuthInputSequence++;
        authoritativeInputSequences.put(frame, offeredAuthInputSequence);
        lastAuthInputMatchStatus = "observed auth input tick=" + frame.getClientTick() + " sequence=" + offeredAuthInputSequence;
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
        authoritativeInputSequences.put(frame, processedAuthInputSequence);
        lastAuthInputMatchStatus = trigger == null
                ? "processed auth input tick=" + frame.getClientTick() + " sequence=" + processedAuthInputSequence
                : "processed auth input tick=" + frame.getClientTick() + " via " + trigger + " sequence=" + processedAuthInputSequence;
        applyClientPoseFrame(frame);
        return processedAuthInputSequence;
    }

    public long authoritativeInputTick(BedrockAuthInputFrame frame) {
        Long sequence = authoritativeInputSequences.get(frame);
        if (sequence != null) {
            return sequence;
        }
        if (frame != null && frame == lastProcessedFrame) {
            return processedAuthInputSequence;
        }
        if (frame != null && frame == lastOfferedFrame) {
            return offeredAuthInputSequence;
        }
        return Math.max(processedAuthInputSequence, offeredAuthInputSequence);
    }

    public void clearMovementInputState() {
        clearTransientMovementInputState();
        trackedPoseState = BedrockClientPoseState.STANDING;
        confirmedBoundingBoxSize = null;
        lastAuthInputMatchStatus = "auth input state cleared";
    }

    /** Clears packet-local input while retaining confirmed pose and dimensions. */
    public void clearTransientMovementInputState() {
        lastOfferedFrame = null;
        lastProcessedFrame = null;
        authoritativeInputSequences.clear();
        lastMoveFrame = null;
        lastPoseAppliedFrame = null;
        lastPoseAppliedState = null;
        pendingStartGlidingAction = false;
        pendingStopGlidingAction = false;
        pendingItemRelease = false;
        pendingStartSpinAttack = false;
        pendingStopSpinAttack = false;
        clearRiptideUseTracking();
        lastAuthInputMatchStatus = "transient auth input state cleared";
    }

    public synchronized void applyAcknowledgedBoundingBoxMetadata(Float width, Float height) {
        if (width == null && height == null
                || width != null && (!Float.isFinite(width) || width <= 0.0F)
                || height != null && (!Float.isFinite(height) || height <= 0.0F)) {
            return;
        }
        if (width != null || height != null) {
            PlayerDimensionsState previous = confirmedBoundingBoxSize;
            if (previous == null) {
                previous = PlayerDimensionsState.DEFAULT;
            }
            confirmedBoundingBoxSize = new PlayerDimensionsState(
                    width == null ? previous.width() : width,
                    height == null ? previous.height() : height);
        }
    }

    public synchronized void applyAcknowledgedPoseMetadata(Boolean crawling, Boolean swimming) {
        if (crawling != null) {
            trackedPoseState = trackedPoseState.withCrawling(crawling);
        }
        if (swimming != null) {
            trackedPoseState = trackedPoseState.withSwimming(swimming);
        }
    }

    public synchronized BedrockMovementState applyConfirmedBoundingBoxSize(
            BedrockMovementState source,
            BedrockInputFrame currentFrame
    ) {
        if (source == null || confirmedBoundingBoxSize == null
                || confirmedBoundingBoxSize.equals(source.acknowledgedPlayerDimensions())) {
            return source;
        }
        BedrockBoundingBoxMode currentMode = BedrockBoundingBoxMode.resolve(source, currentFrame);
        return source.withPlayerDimensions(currentMode, confirmedBoundingBoxSize, true);
    }

    public synchronized String boundingBoxStatus() {
        return "confirmedSize=" + confirmedBoundingBoxSize;
    }

    public void recordItemReleaseAction() {
        pendingItemRelease = true;
    }

    public void recordStartSpinAttackAction() {
        pendingStartSpinAttack = true;
    }

    public void recordStopSpinAttackAction() {
        pendingStopSpinAttack = true;
    }

    public boolean consumeItemReleaseFor(BedrockAuthInputFrame frame) {
        boolean pending = pendingItemRelease;
        pendingItemRelease = false;
        return pending && frame != null;
    }

    public boolean consumeStartSpinAttackFor(BedrockAuthInputFrame frame) {
        boolean pending = pendingStartSpinAttack;
        pendingStartSpinAttack = false;
        return pending && frame != null;
    }

    public boolean consumeStopSpinAttackFor(BedrockAuthInputFrame frame) {
        boolean pending = pendingStopSpinAttack;
        pendingStopSpinAttack = false;
        return pending && frame != null;
    }

    public boolean shouldStartRiptideCharge(BedrockAuthInputFrame frame, boolean riptideAvailable) {
        if (!riptideAvailable || frame == null || !frame.isUsingItem()) {
            return false;
        }
        long inputTick = authoritativeInputTick(frame);
        if (trackedRiptideUseStartInputTick == Long.MIN_VALUE) {
            trackedRiptideUseStartInputTick = inputTick;
            return true;
        }
        return false;
    }

    public void clearRiptideUseTracking() {
        trackedRiptideUseStartInputTick = Long.MIN_VALUE;
    }

    public void recordStartGlidingAction() {
        pendingStartGlidingAction = true;
    }

    public void recordStopGlidingAction() {
        pendingStopGlidingAction = true;
    }

    public boolean consumeStartGlidingActionFor(BedrockAuthInputFrame frame) {
        boolean pending = pendingStartGlidingAction;
        pendingStartGlidingAction = false;
        return pending && frame != null;
    }

    public boolean consumeStopGlidingActionFor(BedrockAuthInputFrame frame) {
        boolean pending = pendingStopGlidingAction;
        pendingStopGlidingAction = false;
        return pending && frame != null;
    }

    private BedrockClientPoseState poseStateForCurrentTick(BedrockAuthInputFrame frame) {
        if (frame != null) {
            if (frame.hasRawInputFlag(PlayerAuthInputData.SNEAKING)) {
                return trackedPoseState.withSneaking(true);
            }
            if (frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_RELEASED_RAW)
                    || frame.isStopSneaking()) {
                return trackedPoseState.withSneaking(false);
            }
        }
        return trackedPoseState;
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
        lastPoseAppliedState = poseStateForCurrentTick(frame);
        trackedPoseState = PoseTransition.applyAll(trackedPoseState, frame);
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
                if (frame.isStopSneaking()
                        || frame.hasRawInputFlag(PlayerAuthInputData.SNEAK_RELEASED_RAW)) {
                    return state.withSneaking(false);
                }
                return frame.hasRawInputFlag(PlayerAuthInputData.SNEAKING)
                        ? state.withSneaking(true)
                        : state;
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

        abstract BedrockClientPoseState apply(
                BedrockClientPoseState state,
                BedrockAuthInputFrame frame);

        static BedrockClientPoseState applyAll(
                BedrockClientPoseState state,
                BedrockAuthInputFrame frame) {
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
        if (lastMoveFrame != null && lastMoveFrame.protocolVersion() != null && lastMoveFrame.protocolVersion().isKnown()) {
            protocolVersion = lastMoveFrame.protocolVersion();
        }
    }

}
