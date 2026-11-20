package ac.cult.cultac.bedrock.protocol;

import ac.cult.cultac.checks.impl.prediction.AuthoredMovementFrame;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

@Getter
public final class BedrockAuthInputFrame implements AuthoredMovementFrame {
    private final UUID playerUuid;
    private final BedrockProtocolVersion protocolVersion;
    private final long clientTick;
    private final int inputMode;
    private final int playMode;
    private final int interactionModel;
    private final int deviceId;
    private final BedrockCoordinateFrame coordinateFrame;

    @Getter(AccessLevel.NONE)
    private final boolean coordinateProvenance;

    private final Vec3 position;
    private final Vec3 packetPosition;
    private final Vec3 delta;
    private final float yaw;
    private final float pitch;
    private final float headYaw;

    @Getter(AccessLevel.NONE)
    private final boolean hasRotation;

    private final BedrockMoveVector moveVector;
    private final long rawInputFlags;
    private final long rawInputFlagsHigh;
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

    @Getter(AccessLevel.NONE)
    private final boolean blockAction;

    private final String authorityMode;
    private final long rewindCorrectionId;
    private final Vec3 reportedEndOfTickVelocity;
    /** Null for captures that did not preserve vehicle identity; -1 means no predicted vehicle. */
    private final Long predictedVehicleId;
    /** The bridge resolves the runtime ID; mount authority still comes from compensated passengers. */
    private final Integer predictedVehicleJavaId;

    private final VehicleRotation vehicleRotation;

    private BedrockAuthInputFrame(Builder builder) {
        this.playerUuid = Objects.requireNonNull(builder.playerUuid, "playerUuid");
        this.protocolVersion = builder.protocolVersion;
        this.clientTick = builder.clientTick;
        this.inputMode = builder.inputMode;
        this.playMode = builder.playMode;
        this.interactionModel = builder.interactionModel;
        this.deviceId = builder.deviceId;
        this.coordinateFrame = builder.coordinateFrame;
        this.coordinateProvenance = builder.coordinateProvenance;
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
        this.predictedVehicleId = builder.predictedVehicleId;
        this.predictedVehicleJavaId = builder.predictedVehicleJavaId;
        this.vehicleRotation = builder.vehicleRotation;
    }

    private BedrockAuthInputFrame(BedrockAuthInputFrame source, BedrockCoordinateFrame frame, Vec3 worldPosition) {
        this.protocolVersion = source.protocolVersion;
        this.clientTick = source.clientTick;
        this.inputMode = source.inputMode;
        this.playMode = source.playMode;
        this.interactionModel = source.interactionModel;
        this.deviceId = source.deviceId;
        this.coordinateFrame = frame;
        this.coordinateProvenance = source.coordinateProvenance;
        this.position = worldPosition;
        this.packetPosition = source.packetPosition;
        this.delta = source.delta;
        this.yaw = source.yaw;
        this.pitch = source.pitch;
        this.headYaw = source.headYaw;
        this.hasRotation = source.hasRotation;
        this.moveVector = source.moveVector;
        this.rawInputFlags = source.rawInputFlags;
        this.rawInputFlagsHigh = source.rawInputFlagsHigh;
        this.jumping = source.jumping;
        this.jumpStarted = source.jumpStarted;
        this.jumpPressedRaw = source.jumpPressedRaw;
        this.jumpCurrentRaw = source.jumpCurrentRaw;
        this.wantUp = source.wantUp;
        this.sneaking = source.sneaking;
        this.startSneaking = source.startSneaking;
        this.stopSneaking = source.stopSneaking;
        this.sprinting = source.sprinting;
        this.swimming = source.swimming;
        this.stopSwimming = source.stopSwimming;
        this.startCrawling = source.startCrawling;
        this.stopCrawling = source.stopCrawling;
        this.startGliding = source.startGliding;
        this.stopGliding = source.stopGliding;
        this.usingItem = source.usingItem;
        this.blockAction = source.blockAction;
        this.authorityMode = source.authorityMode;
        this.rewindCorrectionId = source.rewindCorrectionId;
        this.reportedEndOfTickVelocity = source.reportedEndOfTickVelocity;
        this.predictedVehicleId = source.predictedVehicleId;
        this.predictedVehicleJavaId = source.predictedVehicleJavaId;
        this.vehicleRotation = source.vehicleRotation;
        this.playerUuid = source.playerUuid;
    }

    public boolean hasCoordinateProvenance() {
        return coordinateProvenance;
    }

    /** Called only after resolving the origin against the ordered transport queue. */
    public BedrockAuthInputFrame resolveCoordinates(BedrockCoordinateFrame frame) {
        if (frame.equals(coordinateFrame)) return this;
        Vec3 localFeet = packetPosition == null
                ? coordinateFrame.toLocal(position)
                : new Vec3((float) packetPosition.x, position.y, (float) packetPosition.z);
        return new BedrockAuthInputFrame(this, frame, frame.toWorld(localFeet));
    }

    public static Builder builder(UUID playerUuid) {
        return new Builder(playerUuid);
    }

    public boolean hasMinimumStrictData() {
        return position != null && hasRotation;
    }

    public boolean hasRotation() {
        return hasRotation;
    }

    // Horizontal movement input is evaluated after simulation as required input, not spent before prediction.
    public BedrockMoveVector getMoveVector() {
        return moveVector;
    }

    public boolean hasRawInputFlag(PlayerAuthInputData input) {
        int ordinal = input.ordinal();
        if (ordinal < Long.SIZE) {
            return (rawInputFlags & (1L << ordinal)) != 0L;
        }
        return ordinal < Long.SIZE * 2 && (rawInputFlagsHigh & (1L << (ordinal - Long.SIZE))) != 0L;
    }

    public boolean isStartSwimming() {
        return swimming;
    }

    public boolean hasBlockAction() {
        return blockAction;
    }

    public record VehicleRotation(float yaw, float pitch) {}

    public boolean samePacketAs(BedrockAuthInputFrame other) {
        return other != null
                && Objects.equals(playerUuid, other.playerUuid)
                && Objects.equals(protocolVersion, other.protocolVersion)
                && Objects.equals(coordinateFrame, other.coordinateFrame)
                && coordinateProvenance == other.coordinateProvenance
                && clientTick == other.clientTick
                && inputMode == other.inputMode
                && playMode == other.playMode
                && interactionModel == other.interactionModel
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
                && Objects.equals(predictedVehicleId, other.predictedVehicleId)
                && Objects.equals(predictedVehicleJavaId, other.predictedVehicleJavaId)
                && Objects.equals(vehicleRotation, other.vehicleRotation)
                && rewindCorrectionId == other.rewindCorrectionId;
    }

    @Accessors(fluent = true)
    public static final class Builder {
        private final UUID playerUuid;
        private BedrockProtocolVersion protocolVersion = BedrockProtocolVersion.UNKNOWN;

        @Setter
        private long clientTick = -1L;

        @Setter
        private int inputMode = -1;

        @Setter
        private int playMode = -1;

        @Setter
        private int interactionModel = -1;

        @Setter
        private int deviceId = -1;

        private BedrockCoordinateFrame coordinateFrame = BedrockCoordinateFrame.IDENTITY;

        @Setter
        private boolean coordinateProvenance = true;

        @Setter
        private Vec3 position;

        @Setter
        private Vec3 packetPosition;

        @Setter
        private Vec3 delta;

        private float yaw;
        private float pitch;
        private float headYaw;
        private boolean hasRotation;
        private BedrockMoveVector moveVector;

        @Setter
        private long rawInputFlags;

        @Setter
        private long rawInputFlagsHigh;

        @Setter
        private boolean jumping;

        @Setter
        private boolean jumpStarted;

        @Setter
        private boolean jumpPressedRaw;

        @Setter
        private boolean jumpCurrentRaw;

        @Setter
        private boolean wantUp;

        @Setter
        private boolean sneaking;

        @Setter
        private boolean startSneaking;

        @Setter
        private boolean stopSneaking;

        @Setter
        private boolean sprinting;

        @Setter
        private boolean swimming;

        @Setter
        private boolean stopSwimming;

        @Setter
        private boolean startCrawling;

        @Setter
        private boolean stopCrawling;

        @Setter
        private boolean startGliding;

        @Setter
        private boolean stopGliding;

        @Setter
        private boolean usingItem;

        @Setter
        private boolean blockAction;

        @Setter
        private String authorityMode;

        @Setter
        private long rewindCorrectionId = -1L;

        @Setter
        private Vec3 reportedEndOfTickVelocity;

        @Setter
        private Long predictedVehicleId;

        @Setter
        private Integer predictedVehicleJavaId;

        @Setter
        private VehicleRotation vehicleRotation;

        private Builder(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        public Builder protocolVersion(int protocolVersion) {
            this.protocolVersion = new BedrockProtocolVersion(protocolVersion);
            return this;
        }

        public Builder coordinateFrame(BedrockCoordinateFrame frame) {
            this.coordinateFrame = Objects.requireNonNull(frame, "coordinateFrame");
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

        public Builder startSwimming(boolean startSwimming) {
            this.swimming = startSwimming;
            return this;
        }

        public BedrockAuthInputFrame build() {
            return new BedrockAuthInputFrame(this);
        }
    }
}
