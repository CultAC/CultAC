package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.CultMath;

public class PacketEntityTrackXRot extends PacketEntity {
    public float packetYaw;
    public float interpYaw;
    public int steps = 0;

    public PacketEntityTrackXRot(CultPlayer player, int entityId, int type, double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z);
        this.packetYaw = xRot;
        this.interpYaw = xRot;
    }

    @Override
    public void onMovement(CultPlayer player, boolean highBound) {
        super.onMovement(player, highBound);
        if (player.isBedrockMovement() && bedrockRuntimeId != -1) {
            packetYaw = interpYaw = clientPhysicalYaw;
            steps = 0;
            return;
        }
        if (steps > 0) {
            // MCP-Reborn InterpolationHandler#interpolate uses CultMath.rotLerp, which wraps yaw deltas.
            interpYaw = interpYaw + (CultMath.wrapDegrees(packetYaw - interpYaw) / steps--);
        }
    }
}
