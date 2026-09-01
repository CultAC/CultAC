package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.VanillaMath;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

public class PacketEntityStrider extends PacketEntityRideable {
    public boolean isShaking = false;
    private float walkAnimationSpeed = 0.0F;
    private float walkAnimationPosition = 0.0F;

    public PacketEntityStrider(GrimPlayer player, int entityId, EntityType type, double x, double y, double z) { super(player, entityId, type, x, y, z); }

    public void updateWalkAnimation(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        float targetSpeed = Math.min((float) Math.sqrt(dx * dx + dz * dz) * 4.0F, 1.0F);
        walkAnimationSpeed += (targetSpeed - walkAnimationSpeed) * 0.4F;
        walkAnimationPosition += walkAnimationSpeed;
    }

    public void handleDamageEvent() {
        // MCP-Reborn LivingEntity#handleDamageEvent writes walkAnimation.speed directly.
        walkAnimationSpeed = 1.5F;
    }

    public double getPassengerAttachmentYOffset() {
        float speed = Math.min(0.25F, walkAnimationSpeed);
        float position = walkAnimationPosition * (isBaby ? 3.0F : 1.0F);
        return 0.12F * VanillaMath.cos(position * 1.5F) * 2.0F * speed;
    }
}
