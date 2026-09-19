package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import net.minecraft.world.phys.Vec3;

/** Interpolates ordinary entity updates after their client receipt. */
public final class BedrockEntityInterpolation {
    public record Target(Vec3 packetPosition, float yaw, float pitch, float offset, boolean forceCompletion) { }
    private Target target;
    private Target queued;
    private int remaining = 3;
    private int queuedSteps;

    public BedrockEntityInterpolation(Target target) { this.target = target; }

    public void update(Target next) {
        if (target.forceCompletion() && remaining != 0 && !next.forceCompletion()) {
            queued = next;
            queuedSteps = 3;
        } else {
            target = next;
            remaining = 3;
            queued = null;
        }
    }

    public void tick(CultPlayer player, PacketEntity entity) {
        // A forced interpolation completes before a newer ordinary target can begin.
        if (queued != null) {
            if (remaining == 0) {
                target = queued;
                remaining = queuedSteps;
                queued = null;
            } else queuedSteps = Math.max(1, queuedSteps - 1);
        }
        var coordinates = player.getSetbackTeleportUtil().getActiveBedrockCoordinateFrame();
        Vec3 from = coordinates.toLocal(entity.clientPhysicalPosition);
        Vec3 to = coordinates.toLocal(target.packetPosition());
        float offset = target.offset();
        float x = step((float) from.x, (float) to.x);
        float y = step((float) from.y + offset, (float) to.y) - offset;
        float z = step((float) from.z, (float) to.z);
        Vec3 feet = coordinates.toWorld(new Vec3(x, y, z));
        float yawOffset = entity.isBoat() ? 90.0F : 0.0F;
        float yaw = angle(entity.clientPhysicalYaw + yawOffset, target.yaw()) - yawOffset;
        float pitch = angle(entity.clientPhysicalPitch, target.pitch());
        entity.oldPacketLocation = null;
        entity.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(player, feet.x, feet.y, feet.z, entity), yaw, pitch);
        if (--remaining == 0 && queued == null) entity.bedrockInterpolation = null;
    }

    private float step(float from, float to) {
        return remaining == 1 ? to : from + (to - from) * (1.0F / remaining);
    }

    private float angle(float from, float to) {
        return remaining == 1 ? to : from + net.minecraft.util.Mth.wrapDegrees(to - from) * (1.0F / remaining);
    }
}
