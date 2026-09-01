package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public class PacketEntityHappyGhast extends PacketEntityTrackXRot {
    public boolean hasBodyArmor = false;
    public boolean staysStill = false;
    public float movementSpeedAttribute = 0.05F;
    public float flyingSpeedAttribute = 0.05F;

    public PacketEntityHappyGhast(GrimPlayer player, int entityId, EntityType type, double x, double y, double z, float yaw) {
        super(player, entityId, type, x, y, z, yaw);
    }

    public float getRiddenTickYaw(float controllerYaw) {
        float rootYaw = clientPhysicalYaw;
        return rootYaw + Mth.wrapDegrees(controllerYaw - rootYaw) * 0.08F;
    }

    public float getRiddenTickPitch(float controllerPitch) {
        return controllerPitch * 0.5F;
    }

    public Vec3 getRiddenInput(Vec3 rawInputState, boolean jumping, float controllerPitch) {
        Vec3 controllerInput = getExactControllerModifiedInput(rawInputState);
        float xxa = (float) controllerInput.x;
        float zza = (float) controllerInput.z;
        float vertical = 0.0F;
        float forward = 0.0F;

        if (zza != 0.0F) {
            float pitchRadians = controllerPitch * ((float) Math.PI / 180F);
            vertical = -Mth.sin(pitchRadians);
            forward = Mth.cos(pitchRadians);
            if (zza < 0.0F) {
                vertical *= -0.5F;
                forward *= -0.5F;
            }
        }

        if (jumping) {
            vertical += 0.5F;
        }

        return new Vec3(xxa, vertical, forward).scale(3.9F * flyingSpeedAttribute);
    }

    public Vec3 getTravelInputVector(GrimPlayer player, Vec3 riddenInput, float yaw) {
        double lengthSqr = riddenInput.lengthSqr();
        if (lengthSqr < 1.0E-7D) {
            return Vec3.ZERO;
        }

        Vec3 scaled = (lengthSqr > 1.0D ? riddenInput.normalize() : riddenInput).scale(flyingSpeedAttribute * 5.0F / 3.0F);
        float yawRadians = yaw * ((float) Math.PI / 180F);
        float sin = player.trigHandler.sin(yawRadians);
        float cos = player.trigHandler.cos(yawRadians);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    private Vec3 getExactControllerModifiedInput(Vec3 rawInputState) {
        float xxa = (float) rawInputState.x;
        float zza = (float) rawInputState.z;
        float rawLengthSqr = xxa * xxa + zza * zza;
        if (rawLengthSqr < 1.0E-7F) {
            return Vec3.ZERO;
        }

        float rawLength = Mth.sqrt(rawLengthSqr);
        float normalizedX = xxa / rawLength;
        float normalizedZ = zza / rawLength;

        float modifiedX = normalizedX * 0.98F;
        float modifiedZ = normalizedZ * 0.98F;
        float modifiedLength = Mth.sqrt(modifiedX * modifiedX + modifiedZ * modifiedZ);
        if (modifiedLength > 0.0F) {
            float unitX = modifiedX / modifiedLength;
            float unitZ = modifiedZ / modifiedLength;
            float absX = Math.abs(unitX);
            float absZ = Math.abs(unitZ);
            float ratio = absZ > absX ? absX / absZ : absZ / absX;
            float distanceToUnitSquare = Mth.sqrt(1.0F + Mth.square(ratio));
            float scale = Math.min(modifiedLength * distanceToUnitSquare, 1.0F);
            modifiedX = unitX * scale;
            modifiedZ = unitZ * scale;
        }

        return new Vec3(modifiedX, 0.0D, modifiedZ);
    }
}
