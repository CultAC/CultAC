package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.world.entity.EntityType;

/** Client-visible state used by AbstractNautilus#travelRidden. */
public final class PacketEntityNautilus extends PacketEntityTrackXRot {
    public boolean hasSaddle;
    public boolean dashing;
    public int dashCooldown;
    public double movementSpeedAttribute = 1.0D;
    public double pendingJumpScale;
    public double nextPendingJumpScale;

    public PacketEntityNautilus(GrimPlayer player, int entityId, EntityType type,
                                double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z, xRot);
        this.stepHeightAttribute = 1.0D;
    }

    public void tickClientState() {
        if (dashing && dashCooldown < 35) {
            dashing = false;
        }
        if (dashCooldown > 0) {
            dashCooldown--;
        }
        if (nextPendingJumpScale > 0.0D) {
            pendingJumpScale = nextPendingJumpScale;
            nextPendingJumpScale = 0.0D;
        }
    }

    public void setDashingFromMetadata(boolean dashing) {
        if (this.dashing != dashing && dashCooldown == 0) {
            dashCooldown = 40;
        }
        this.dashing = dashing;
    }
}
