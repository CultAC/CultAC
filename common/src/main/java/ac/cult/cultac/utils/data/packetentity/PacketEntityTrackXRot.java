package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;

public class PacketEntityTrackXRot extends PacketEntity {
    public float packetYaw;
    public float interpYaw;
    public int steps = 0;

    public PacketEntityTrackXRot(CultPlayer player, int entityId, EntityType type, double x, double y, double z, float xRot) { super(player, entityId, type, x, y, z);
        this.packetYaw = xRot;
        this.interpYaw = xRot;
    }

    @Override
    public void onMovement(CultPlayer player, boolean highBound) {
        super.onMovement(player, highBound);
        if (steps > 0) {
            // MCP-Reborn InterpolationHandler#interpolate uses Mth.rotLerp, which wraps yaw deltas.
            interpYaw = interpYaw + (Mth.wrapDegrees(packetYaw - interpYaw) / steps--);
        }
    }
}
