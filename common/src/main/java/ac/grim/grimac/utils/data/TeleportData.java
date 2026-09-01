package ac.grim.grimac.utils.data;

import ac.grim.grimac.network.protocol.teleport.RelativeFlag;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.Setter;

@Getter
public class TeleportData {
    Vec3 location;
    RelativeFlag flags;
    Vec3 deltaMovement;
    float sourceYaw;
    float sourcePitch;
    float finalYaw;
    float finalPitch;
    @Setter
    int transaction;
    @Setter
    int teleportId;
    @Setter
    boolean positionOnly;
    @Setter
    boolean rotationOnly;
    @Setter
    boolean sentWhileVehicle;
    @Setter
    boolean sentDuringVehicleDismount;
    @Setter
    Boolean bedrockOnGround;
    @Setter
    boolean bedrockTransportOnly;
    @Setter
    long bedrockTransportRevision;

    public TeleportData(Vec3 location, RelativeFlag flags, Vec3 deltaMovement, int transaction, int teleportId) {
        this(location, flags, deltaMovement, transaction, teleportId, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    public TeleportData(Vec3 location, RelativeFlag flags, Vec3 deltaMovement, int transaction, int teleportId, float sourceYaw, float sourcePitch, float finalYaw, float finalPitch) {
        this.location = location;
        this.flags = flags;
        this.deltaMovement = deltaMovement;
        this.sourceYaw = sourceYaw;
        this.sourcePitch = sourcePitch;
        this.finalYaw = finalYaw;
        this.finalPitch = finalPitch;
        this.transaction = transaction;
        this.teleportId = teleportId;
        this.positionOnly = false;
        this.rotationOnly = false;
        this.sentWhileVehicle = false;
        this.sentDuringVehicleDismount = false;
        this.bedrockOnGround = null;
        this.bedrockTransportOnly = false;
        this.bedrockTransportRevision = -1L;
    }

    public TeleportData(Vec3 location, RelativeFlag flags, int transaction, int teleportId) {
        this(location, flags, Vec3.ZERO, transaction, teleportId);
    }

    public TeleportData copyWithLocation(Vec3 location) {
        TeleportData copy = new TeleportData(location, flags, deltaMovement, transaction, teleportId, sourceYaw, sourcePitch, finalYaw, finalPitch);
        copy.positionOnly = positionOnly;
        copy.rotationOnly = rotationOnly;
        copy.sentWhileVehicle = sentWhileVehicle;
        copy.sentDuringVehicleDismount = sentDuringVehicleDismount;
        copy.bedrockOnGround = bedrockOnGround;
        copy.bedrockTransportOnly = bedrockTransportOnly;
        copy.bedrockTransportRevision = bedrockTransportRevision;
        return copy;
    }

    public void applyBedrockTransportBoundary(Vec3 physicalFeetPosition, boolean onGround, long revision) {
        int positionMask = RelativeFlag.X.getMask() | RelativeFlag.Y.getMask() | RelativeFlag.Z.getMask();
        this.location = physicalFeetPosition;
        this.flags = new RelativeFlag(flags.getMask() & ~positionMask);
        this.positionOnly = true;
        this.bedrockOnGround = onGround;
        this.bedrockTransportRevision = revision;
    }

    public boolean isAbsolute() {
        return !isRelativeX() && !isRelativeY() && !isRelativeZ();
    }

    public Vec3 applyToVelocity(Vec3 vector) {
        Vec3 transformed = rotateDeltaIfRequired(vector);
        return new Vec3(
                applyDeltaAxis(transformed.x, deltaMovement.x, isRelativeDeltaX()),
                applyDeltaAxis(transformed.y, deltaMovement.y, isRelativeDeltaY()),
                applyDeltaAxis(transformed.z, deltaMovement.z, isRelativeDeltaZ())
        );
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

    public Vec3 modifyVector(Vec3 vector) {
        return new Vec3(
                isRelativeX() ? vector.x : 0,
                isRelativeY() ? vector.y : 0,
                isRelativeZ() ? vector.z : 0
        );
    }

    private Vec3 rotateDeltaIfRequired(Vec3 vector) {
        if (!isRotateDelta()) {
            return vector;
        }

        float yawDelta = sourceYaw - finalYaw;
        float pitchDelta = sourcePitch - finalPitch;
        return vector
                .xRot((float) Math.toRadians(pitchDelta))
                .yRot((float) Math.toRadians(yawDelta));
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
