package ac.cult.cultac.utils.data;

import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportOperation;
import ac.cult.cultac.bedrock.protocol.BedrockTeleportProvenance;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.teleport.RelativeFlag;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPlayerRotation;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.math.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
public class TeleportData {
    @Setter
    BedrockCoordinateFrame bedrockCoordinateFrame = BedrockCoordinateFrame.IDENTITY;

    @Setter
    boolean bedrockOriginConfirmed = true;

    @Setter
    Vec3 bedrockLocalPacketTarget;

    @Setter
    BedrockTeleportOperation bedrockOperation;

    public BedrockTeleportProvenance getBedrockProvenance() {
        return bedrockOperation == null ? BedrockTeleportProvenance.GEYSER : bedrockOperation.provenance();
    }

    /**
     * Whether this teleport's answer can complete a Cult setback: any Java teleport, and on Bedrock only Cult's own
     * emission. A teleport Geyser or GFP emitted never answers Cult's setback.
     */
    public boolean mayCompleteCultSetback() {
        return bedrockTransportRevision < 0L || getBedrockProvenance() == BedrockTeleportProvenance.CULT_SETBACK;
    }

    Vec3 location;
    RelativeFlag flags;
    Vec3 deltaMovement;
    float sourceYaw;
    float sourcePitch;
    float finalYaw;
    float finalPitch;
    private ClientVersion clientVersion = ClientVersion.V_26_3;
    private float velocityPitch;
    /** A player_position's own rotation, absolute or relative by {@link #flags}; NaN when the source is given. */
    private float packetYaw = Float.NaN;

    private float packetPitch = Float.NaN;

    @Setter
    int transaction;

    @Setter
    int teleportId;

    @Setter
    boolean positionOnly;

    @Setter
    boolean rotationOnly;

    @Setter
    ClientboundPlayerRotation playerRotation;

    /** A ping the client answers in the same bundle task before it handles {@link #playerRotation}. */
    @Setter
    int bundleTransaction = Integer.MAX_VALUE;

    @Setter
    boolean sentWhileVehicle;

    @Setter
    boolean sentDuringVehicleDismount;

    /** The client sent this pending teleport's whole answer: its ID with the position, or its ID and a PosRot. */
    @Setter
    boolean answered;

    /** CultAC emitted this player_position; its client replies are not backend movement. */
    @Setter
    boolean authored;

    @Setter
    Boolean bedrockOnGround;

    /** A Bedrock dimension change's placement: the actor rests there, and its next actor tick travels. */
    @Setter
    boolean bedrockDimensionChange;

    /**
     * The onGround this teleport's client bundle leaves the player with, through ClientPacketListener#handleTeleportEntity
     * on the player itself; null when the bundle sets none.
     */
    @Setter
    Boolean sentOnGround;

    @Setter
    long bedrockTransportRevision;

    public TeleportData(Vec3 location, RelativeFlag flags, Vec3 deltaMovement, int transaction, int teleportId) {
        this(location, flags, deltaMovement, transaction, teleportId, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    public TeleportData(
            Vec3 location,
            RelativeFlag flags,
            Vec3 deltaMovement,
            int transaction,
            int teleportId,
            float sourceYaw,
            float sourcePitch,
            float finalYaw,
            float finalPitch) {
        this.location = location;
        this.flags = flags;
        this.deltaMovement = deltaMovement;
        this.sourceYaw = sourceYaw;
        this.sourcePitch = sourcePitch;
        this.finalYaw = finalYaw;
        this.finalPitch = finalPitch;
        this.velocityPitch = finalPitch;
        this.transaction = transaction;
        this.teleportId = teleportId;
        this.positionOnly = false;
        this.rotationOnly = false;
        this.sentWhileVehicle = false;
        this.sentDuringVehicleDismount = false;
        this.bedrockOnGround = null;
        this.bedrockTransportRevision = -1L;
    }

    public TeleportData(Vec3 location, RelativeFlag flags, int transaction, int teleportId) {
        this(location, flags, Vec3.ZERO, transaction, teleportId);
    }

    public TeleportData copyWithLocation(Vec3 location) {
        TeleportData copy = new TeleportData(
                location, flags, deltaMovement, transaction, teleportId, sourceYaw, sourcePitch, finalYaw, finalPitch);
        copy.bedrockOriginConfirmed = bedrockOriginConfirmed;
        copy.bedrockCoordinateFrame = bedrockCoordinateFrame;
        copy.bedrockLocalPacketTarget = bedrockLocalPacketTarget;
        copy.positionOnly = positionOnly;
        copy.rotationOnly = rotationOnly;
        copy.playerRotation = playerRotation;
        copy.sentWhileVehicle = sentWhileVehicle;
        copy.sentDuringVehicleDismount = sentDuringVehicleDismount;
        copy.authored = authored;
        copy.clientVersion = clientVersion;
        copy.velocityPitch = velocityPitch;
        copy.packetYaw = packetYaw;
        copy.packetPitch = packetPitch;
        copy.bedrockOnGround = bedrockOnGround;
        copy.bedrockDimensionChange = bedrockDimensionChange;
        copy.sentOnGround = sentOnGround;
        copy.bedrockTransportRevision = bedrockTransportRevision;
        copy.bedrockOperation = bedrockOperation;
        return copy;
    }

    /** calculateAbsolute clamps before rotating deltas from 1.21.5; older setXRot clamps only afterward. */
    public void configureJavaRotation(ClientVersion version, float absolutePitch) {
        clientVersion = version;
        velocityPitch = version.isOlderThan(ClientVersion.V_1_21_5) ? absolutePitch : clampPitch(absolutePitch);
        finalPitch = clientPitch(version, absolutePitch);
    }

    public static float clientPitch(ClientVersion version, float absolutePitch) {
        return clampPitch(version.isOlderThan(ClientVersion.V_1_21_5) ? absolutePitch % 360.0F : absolutePitch);
    }

    public void setPacketRotation(float yaw, float pitch) {
        packetYaw = yaw;
        packetPitch = pitch;
    }

    /**
     * ClientPacketListener#handleMovePlayer builds PositionMoveRotation#of from the rotation the client holds when it
     * handles the packet, not the one it had reported when the packet was sent: {@code yaw}/{@code pitch}, its last
     * report or what an earlier teleport it handled since left.
     */
    public TeleportData resolvePositionRotation(float yaw, float pitch) {
        if (Float.isNaN(packetYaw)) return this;
        TeleportData copy = copyWithLocation(location);
        copy.sourceYaw = yaw;
        copy.sourcePitch = pitch;
        copy.finalYaw = (flags.isSet(RelativeFlag.Y_ROT.getMask()) ? yaw : 0.0F) + packetYaw;
        copy.configureJavaRotation(
                clientVersion, (flags.isSet(RelativeFlag.X_ROT.getMask()) ? pitch : 0.0F) + packetPitch);
        return copy;
    }

    /** Resolve handleRotatePlayer against the last rotation reported before its immediate echo. */
    public TeleportData resolvePlayerRotation(float yaw, float pitch) {
        if (playerRotation == null) return this;
        TeleportData copy = copyWithLocation(location);
        copy.sourceYaw = yaw;
        copy.sourcePitch = pitch;
        copy.finalYaw = (playerRotation.relativeYaw() ? yaw : 0.0F) + playerRotation.yaw();
        copy.configureJavaRotation(
                clientVersion, (playerRotation.relativePitch() ? pitch : 0.0F) + playerRotation.pitch());
        return copy;
    }

    /**
     * Resolve handleRotatePlayer's echo {@code yaw}/{@code pitch}. Its relative axes start from the client's live
     * rotation, which becomes the source; an absolute axis keeps the last reported {@code lastYaw}/{@code lastPitch}.
     */
    public TeleportData resolvePlayerRotationEcho(float yaw, float pitch, float lastYaw, float lastPitch) {
        if (playerRotation == null) return this;
        TeleportData copy = copyWithLocation(location);
        copy.sourceYaw = lastYaw;
        copy.sourcePitch = lastPitch;
        if (playerRotation.relativeYaw()) {
            copy.finalYaw = yaw;
            copy.sourceYaw = yaw - playerRotation.yaw();
        }
        if (playerRotation.relativePitch()) {
            copy.finalPitch = clampPitch(pitch);
            copy.sourcePitch = pitchBeforeRelativeRotation(copy.finalPitch, playerRotation.pitch(), lastPitch);
        }
        return copy;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    /**
     * The live pitch that PositionMoveRotation#calculateAbsolute added {@code relative} to for {@code echo}.
     * calculateAbsolute clamps the sum to [-90, 90], so every live pitch in a range produces the same echo at a bound.
     */
    public static float pitchBeforeRelativeRotation(float echo, float relative, float lastPitch) {
        float low;
        float high;
        if (echo >= 90.0F) {
            low = Math.max(-90.0F, 90.0F - relative);
            high = 90.0F;
        } else if (echo <= -90.0F) {
            low = -90.0F;
            high = Math.min(90.0F, -90.0F - relative);
        } else {
            return echo - relative;
        }
        if (low > high) return echo - relative;
        return Math.max(low, Math.min(high, lastPitch));
    }

    public void applyBedrockTransportBoundary(Vec3 physicalFeetPosition, boolean onGround, long revision) {
        int positionMask = RelativeFlag.X.getMask() | RelativeFlag.Y.getMask() | RelativeFlag.Z.getMask();
        this.location = physicalFeetPosition;
        this.flags = new RelativeFlag(flags.getMask() & ~positionMask);
        this.positionOnly = true;
        this.bedrockOnGround = onGround;
        this.bedrockTransportRevision = revision;
    }

    public Vec3 applyToVelocity(Vec3 vector) {
        Vec3 transformed = rotateDeltaIfRequired(vector);
        return new Vec3(
                applyDeltaAxis(transformed.x, deltaMovement.x, isRelativeDeltaX()),
                applyDeltaAxis(transformed.y, deltaMovement.y, isRelativeDeltaY()),
                applyDeltaAxis(transformed.z, deltaMovement.z, isRelativeDeltaZ()));
    }

    public Vec3 transformInheritedVelocity(Vec3 vector) {
        Vec3 transformed = rotateDeltaIfRequired(vector);
        if (!isRelativeDeltaX()) {
            transformed = transformed.with(Direction.Axis.X, 0);
        }
        if (!isRelativeDeltaY()) {
            transformed = transformed.with(Direction.Axis.Y, 0);
        }
        if (!isRelativeDeltaZ()) {
            transformed = transformed.with(Direction.Axis.Z, 0);
        }
        return transformed;
    }

    private Vec3 rotateDeltaIfRequired(Vec3 vector) {
        if (!isRotateDelta()) {
            return vector;
        }

        float yawDelta = sourceYaw - finalYaw;
        float pitchDelta = sourcePitch - velocityPitch;
        return vector.xRot(clientVersion, (float) Math.toRadians(pitchDelta))
                .yRot(clientVersion, (float) Math.toRadians(yawDelta));
    }

    private double applyDeltaAxis(double current, double change, boolean relative) {
        return relative ? current + change : change;
    }

    public boolean isRelativeX() {
        return flags.isSet(RelativeFlag.X.getMask());
    }

    public boolean isRelativeY() {
        return flags.isSet(RelativeFlag.Y.getMask());
    }

    public boolean isRelativeZ() {
        return flags.isSet(RelativeFlag.Z.getMask());
    }

    public boolean isRelativeDeltaX() {
        return flags.isSet(RelativeFlag.DELTA_X.getMask());
    }

    public boolean isRelativeDeltaY() {
        return flags.isSet(RelativeFlag.DELTA_Y.getMask());
    }

    public boolean isRelativeDeltaZ() {
        return flags.isSet(RelativeFlag.DELTA_Z.getMask());
    }

    public boolean isRotateDelta() {
        return flags.isSet(RelativeFlag.ROTATE_DELTA.getMask());
    }
}
