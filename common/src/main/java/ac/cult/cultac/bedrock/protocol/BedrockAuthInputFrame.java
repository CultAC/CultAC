package ac.cult.cultac.bedrock.protocol;

import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

public final class BedrockAuthInputFrame implements AuthoredMovementFrame {
    private final UUID playerUuid;
    private final BedrockProtocolVersion protocolVersion;
    private final long clientTick;
    private final int inputMode;
    private final int playMode;
    private final int deviceId;
    private final Vec3 position;
    private final Vec3 packetPosition;
    private final Vec3 delta;
    private final float yaw;
    private final float pitch;
    private final float headYaw;
    private final boolean hasRotation;
    private final BedrockMoveVector moveVector;
    private final long rawInputFlags;
    private final long rawInputFlagsHigh;
    private final boolean projectedOnGround;
    private final boolean jumping;
    private final boolean jumpStarted;
    private final boolean jumpPressedRaw;
    private final boolean jumpCurrentRaw;
    private final boolean wantUp;
    private final boolean sneaking;
    private final boolean startSneaking;
    private final boolean stopSneaking;
    private final boolean sprinting;
    private final boolean swimming;
    private final boolean stopSwimming;
    private final boolean startCrawling;
    private final boolean stopCrawling;
    private final boolean startGliding;
    private final boolean stopGliding;
    private final boolean usingItem;
    private final boolean blockAction;
    private final String authorityMode;
    private final long rewindCorrectionId;
    private final Vec3 reportedEndOfTickVelocity;

    private BedrockAuthInputFrame(Builder builder) {
        this.playerUuid = Objects.requireNonNull(builder.playerUuid, "playerUuid");
        this.protocolVersion = builder.protocolVersion;
        this.clientTick = builder.clientTick;
        this.inputMode = builder.inputMode;
        this.playMode = builder.playMode;
        this.deviceId = builder.deviceId;
        this.position = builder.position;
        this.packetPosition = builder.packetPosition;
        this.delta = builder.delta;
        this.yaw = builder.yaw;
        this.pitch = builder.pitch;
        this.headYaw = builder.headYaw;
        this.hasRotation = builder.hasRotation;
        this.moveVector = builder.moveVector;
        this.rawInputFlags = builder.rawInputFlags;
        this.rawInputFlagsHigh = builder.rawInputFlagsHigh;
        this.projectedOnGround = builder.projectedOnGround;
        this.jumping = builder.jumping;
        this.jumpStarted = builder.jumpStarted;
        this.jumpPressedRaw = builder.jumpPressedRaw;
        this.jumpCurrentRaw = builder.jumpCurrentRaw;
        this.wantUp = builder.wantUp;
        this.sneaking = builder.sneaking;
        this.startSneaking = builder.startSneaking;
        this.stopSneaking = builder.stopSneaking;
        this.sprinting = builder.sprinting;
        this.swimming = builder.swimming;
        this.stopSwimming = builder.stopSwimming;
        this.startCrawling = builder.startCrawling;
        this.stopCrawling = builder.stopCrawling;
        this.startGliding = builder.startGliding;
        this.stopGliding = builder.stopGliding;
        this.usingItem = builder.usingItem;
        this.blockAction = builder.blockAction;
        this.authorityMode = builder.authorityMode;
        this.rewindCorrectionId = builder.rewindCorrectionId;
        this.reportedEndOfTickVelocity = builder.reportedEndOfTickVelocity;
    }

    public static Builder builder(UUID playerUuid) {
        return new Builder(playerUuid);
    }

    public boolean hasMinimumStrictData() {
        return position != null
                && hasRotation;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public BedrockProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public long getClientTick() {
        return clientTick;
    }

    public int getInputMode() {
        return inputMode;
    }

    public int getPlayMode() {
        return playMode;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public Vec3 getPosition() {
        return position;
    }

    public Vec3 getPacketPosition() {
        return packetPosition;
    }

    public Vec3 getDelta() {
        return delta;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getHeadYaw() {
        return headYaw;
    }

    public boolean hasRotation() {
        return hasRotation;
    }

    // Horizontal movement input is evaluated after simulation as required input, not spent before prediction.
    public BedrockMoveVector getMoveVector() {
        return moveVector;
    }

    public long getRawInputFlags() {
        return rawInputFlags;
    }

    public long getRawInputFlagsHigh() {
        return rawInputFlagsHigh;
    }

    public boolean isProjectedOnGround() {
        return projectedOnGround;
    }

    public boolean hasRawInputFlag(PlayerAuthInputData input) {
        int ordinal = input.ordinal();
        if (ordinal < Long.SIZE) {
            return (rawInputFlags & (1L << ordinal)) != 0L;
        }
        return ordinal < Long.SIZE * 2
                && (rawInputFlagsHigh & (1L << (ordinal - Long.SIZE))) != 0L;
    }

    public boolean isJumping() {
        return jumping;
    }

    public boolean isJumpStarted() {
        return jumpStarted;
    }

    public boolean isJumpPressedRaw() {
        return jumpPressedRaw;
    }

    public boolean isJumpCurrentRaw() {
        return jumpCurrentRaw;
    }

    public boolean isWantUp() {
        return wantUp;
    }

    public boolean isSneaking() {
        return sneaking;
    }

    public boolean isStartSneaking() {
        return startSneaking;
    }

    public boolean isStopSneaking() {
        return stopSneaking;
    }

    public boolean isSprinting() {
        return sprinting;
    }

    public boolean isSwimming() {
        return swimming;
    }

    public boolean isStartSwimming() {
        return swimming;
    }

    public boolean isStopSwimming() {
        return stopSwimming;
    }

    public boolean isStartCrawling() {
        return startCrawling;
    }

    public boolean isStopCrawling() {
        return stopCrawling;
    }

    public boolean isStartGliding() {
        return startGliding;
    }

    public boolean isStopGliding() {
        return stopGliding;
    }

    public boolean isUsingItem() {
        return usingItem;
    }

    public boolean hasBlockAction() {
        return blockAction;
    }

    public String getAuthorityMode() {
        return authorityMode;
    }

    public long getRewindCorrectionId() {
        return rewindCorrectionId;
    }

    public Vec3 getReportedEndOfTickVelocity() {
        return reportedEndOfTickVelocity;
    }

    public boolean samePacketAs(BedrockAuthInputFrame other) {
        return other != null
                && clientTick == other.clientTick
                && inputMode == other.inputMode
                && playMode == other.playMode
                && deviceId == other.deviceId
                && Objects.equals(position, other.position)
                && Objects.equals(packetPosition, other.packetPosition)
                && Objects.equals(delta, other.delta)
                && Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0
                && Float.compare(headYaw, other.headYaw) == 0
                && hasRotation == other.hasRotation
                && Objects.equals(moveVector, other.moveVector)
                && rawInputFlags == other.rawInputFlags
                && rawInputFlagsHigh == other.rawInputFlagsHigh
                && projectedOnGround == other.projectedOnGround
                && jumping == other.jumping
                && jumpStarted == other.jumpStarted
                && jumpPressedRaw == other.jumpPressedRaw
                && jumpCurrentRaw == other.jumpCurrentRaw
                && wantUp == other.wantUp
                && sneaking == other.sneaking
                && startSneaking == other.startSneaking
                && stopSneaking == other.stopSneaking
                && sprinting == other.sprinting
                && swimming == other.swimming
                && stopSwimming == other.stopSwimming
                && startCrawling == other.startCrawling
                && stopCrawling == other.stopCrawling
                && startGliding == other.startGliding
                && stopGliding == other.stopGliding
                && usingItem == other.usingItem
                && blockAction == other.blockAction
                && Objects.equals(authorityMode, other.authorityMode)
                && rewindCorrectionId == other.rewindCorrectionId;
    }

    public static final class Builder {
        private final UUID playerUuid;
        private BedrockProtocolVersion protocolVersion = BedrockProtocolVersion.UNKNOWN;
        private long clientTick = -1L;
        private int inputMode = -1;
        private int playMode = -1;
        private int deviceId = -1;
        private Vec3 position;
        private Vec3 packetPosition;
        private Vec3 delta;
        private float yaw;
        private float pitch;
        private float headYaw;
        private boolean hasRotation;
        private BedrockMoveVector moveVector;
        private long rawInputFlags;
        private long rawInputFlagsHigh;
        private boolean projectedOnGround;
        private boolean jumping;
        private boolean jumpStarted;
        private boolean jumpPressedRaw;
        private boolean jumpCurrentRaw;
        private boolean wantUp;
        private boolean sneaking;
        private boolean startSneaking;
        private boolean stopSneaking;
        private boolean sprinting;
        private boolean swimming;
        private boolean stopSwimming;
        private boolean startCrawling;
        private boolean stopCrawling;
        private boolean startGliding;
        private boolean stopGliding;
        private boolean usingItem;
        private boolean blockAction;
        private String authorityMode;
        private long rewindCorrectionId = -1L;
        private Vec3 reportedEndOfTickVelocity;

        private Builder(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        public Builder protocolVersion(int protocolVersion) {
            this.protocolVersion = new BedrockProtocolVersion(protocolVersion);
            return this;
        }

        public Builder clientTick(long clientTick) {
            this.clientTick = clientTick;
            return this;
        }

        public Builder inputMode(int inputMode) {
            this.inputMode = inputMode;
            return this;
        }

        public Builder playMode(int playMode) {
            this.playMode = playMode;
            return this;
        }

        public Builder deviceId(int deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        public Builder position(Vec3 position) {
            this.position = position;
            return this;
        }

        public Builder packetPosition(Vec3 packetPosition) {
            this.packetPosition = packetPosition;
            return this;
        }

        public Builder delta(Vec3 delta) {
            this.delta = delta;
            return this;
        }

        public Builder rotation(float yaw, float pitch, float headYaw) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.headYaw = headYaw;
            this.hasRotation = true;
            return this;
        }

        public Builder moveVector(float x, float z) {
            this.moveVector = new BedrockMoveVector(x, z);
            return this;
        }

        public Builder rawInputFlags(long rawInputFlags) {
            this.rawInputFlags = rawInputFlags;
            return this;
        }

        public Builder rawInputFlagsHigh(long rawInputFlagsHigh) {
            this.rawInputFlagsHigh = rawInputFlagsHigh;
            return this;
        }

        public Builder projectedOnGround(boolean projectedOnGround) {
            this.projectedOnGround = projectedOnGround;
            return this;
        }

        public Builder jumping(boolean jumping) {
            this.jumping = jumping;
            return this;
        }

        public Builder jumpStarted(boolean jumpStarted) {
            this.jumpStarted = jumpStarted;
            return this;
        }

        public Builder jumpPressedRaw(boolean jumpPressedRaw) {
            this.jumpPressedRaw = jumpPressedRaw;
            return this;
        }

        public Builder jumpCurrentRaw(boolean jumpCurrentRaw) {
            this.jumpCurrentRaw = jumpCurrentRaw;
            return this;
        }

        public Builder wantUp(boolean wantUp) {
            this.wantUp = wantUp;
            return this;
        }

        public Builder sneaking(boolean sneaking) {
            this.sneaking = sneaking;
            return this;
        }

        public Builder startSneaking(boolean startSneaking) {
            this.startSneaking = startSneaking;
            return this;
        }

        public Builder stopSneaking(boolean stopSneaking) {
            this.stopSneaking = stopSneaking;
            return this;
        }

        public Builder sprinting(boolean sprinting) {
            this.sprinting = sprinting;
            return this;
        }

        public Builder swimming(boolean swimming) {
            this.swimming = swimming;
            return this;
        }

        public Builder startSwimming(boolean startSwimming) {
            this.swimming = startSwimming;
            return this;
        }

        public Builder stopSwimming(boolean stopSwimming) {
            this.stopSwimming = stopSwimming;
            return this;
        }

        public Builder startCrawling(boolean startCrawling) {
            this.startCrawling = startCrawling;
            return this;
        }

        public Builder stopCrawling(boolean stopCrawling) {
            this.stopCrawling = stopCrawling;
            return this;
        }

        public Builder startGliding(boolean startGliding) {
            this.startGliding = startGliding;
            return this;
        }

        public Builder stopGliding(boolean stopGliding) {
            this.stopGliding = stopGliding;
            return this;
        }

        public Builder usingItem(boolean usingItem) {
            this.usingItem = usingItem;
            return this;
        }

        public Builder blockAction(boolean blockAction) {
            this.blockAction = blockAction;
            return this;
        }

        public Builder authorityMode(String authorityMode) {
            this.authorityMode = authorityMode;
            return this;
        }

        public Builder rewindCorrectionId(long rewindCorrectionId) {
            this.rewindCorrectionId = rewindCorrectionId;
            return this;
        }

        public Builder reportedEndOfTickVelocity(Vec3 reportedEndOfTickVelocity) {
            this.reportedEndOfTickVelocity = reportedEndOfTickVelocity;
            return this;
        }

        public BedrockAuthInputFrame build() {
            return new BedrockAuthInputFrame(this);
        }
    }
}
