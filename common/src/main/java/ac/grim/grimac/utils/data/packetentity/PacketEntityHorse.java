package ac.grim.grimac.utils.data.packetentity;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.nmsutil.EntityTypeUtil;
import ac.grim.grimac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.entity.EntityType;

public class PacketEntityHorse extends PacketEntityTrackXRot {
    public boolean isRearing = false;
    public boolean hasSaddle = false;
    public boolean isTame = false;
    public double jumpStrength = 0.7;
    public double nextHorseJump = 0;
    public double horseJump = 0;
    public boolean allowStandSliding = false;
    public boolean nextAllowStandSliding = false;
    private int clientRidingJumpStandTicks = 0;
    private int nextClientRidingJumpStandTicks = 0;
    public float movementSpeedAttribute = 0.225f;
    public float standAnim = 0.0F;
    public float standAnimO = 0.0F;

    public PacketEntityHorse(GrimPlayer player, int entityId, EntityType type, double x, double y, double z, float xRot) { super(player, entityId, type, x, y, z, xRot);

        if (EntityTypeUtil.isChestedHorseFamily(type)) {
            jumpStrength = 0.5;
            movementSpeedAttribute = 0.175f;
        }

        if (type == EntityTypesCompat.ZOMBIE_HORSE || type == EntityTypesCompat.SKELETON_HORSE) {
            movementSpeedAttribute = 0.2f;
        }
    }

    @Override
    public void onMovement(GrimPlayer player, boolean highBound) {
        super.onMovement(player, highBound);
        tickStandAnimation();
    }

    public void tickClientAnimation() {
        tickStandAnimation();
    }

    private void tickStandAnimation() {
        // MCP-Reborn AbstractHorse#tick decrements standCounter before updating standAnim.
        boolean clientJumpStanding = clientRidingJumpStandTicks > 0 && --clientRidingJumpStandTicks > 0;
        boolean standing = isRearing || clientJumpStanding;

        // MCP-Reborn AbstractHorse#tick updates standAnimO/standAnim from the visible standing flag.
        standAnimO = standAnim;
        if (standing) {
            standAnim += (1.0F - standAnim) * 0.4F + 0.05F;
            if (standAnim > 1.0F) {
                standAnim = 1.0F;
            }
        } else {
            // MCP-Reborn AbstractHorse#tick clears allowStandSliding once the
            // synced standing flag drops.
            allowStandSliding = false;
            standAnim += (0.8F * standAnim * standAnim * standAnim - standAnim) * 0.6F - 0.05F;
            if (standAnim < 0.0F) {
                standAnim = 0.0F;
            }
        }

        if (nextClientRidingJumpStandTicks > 0) {
            clientRidingJumpStandTicks = Math.max(clientRidingJumpStandTicks, nextClientRidingJumpStandTicks);
            nextClientRidingJumpStandTicks = 0;
        }
    }

    public void armClientRidingJumpStand() {
        // MCP-Reborn AbstractHorse#onPlayerJump calls standIfPossible(), which
        // sets the client-visible standing flag for 20 ticks. Because the root
        // horse tick already ran before LocalPlayer#rideTick sends the packet,
        // arm this for the next root horse tick rather than the current one.
        nextClientRidingJumpStandTicks = Math.max(nextClientRidingJumpStandTicks, 20);
    }

    public void setRearingFromMetadata(boolean rearing) {
        isRearing = rearing;
        // A horse flags metadata packet writes the same client-visible bit that
        // local setStanding()/clearStanding() writes, so the packet result owns
        // the visible standing state once it is processed.
        clientRidingJumpStandTicks = 0;
        nextClientRidingJumpStandTicks = 0;
    }
}
