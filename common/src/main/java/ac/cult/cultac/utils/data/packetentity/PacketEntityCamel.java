package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.EntityPose;

public class PacketEntityCamel extends PacketEntityHorse {

    public boolean dashing = false;
    public int dashCooldown;
    public boolean lastPredictedInLiquid;
    // Camel LAST_POSE_CHANGE_TICK: negative while sitting; its magnitude is the game time of the last pose change.
    public long lastPoseChangeTick;
    // Entity#firstTick: true until the camel's first client tick.
    private boolean firstTick = true;

    public PacketEntityCamel(CultPlayer player, int entityId, int type, double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z, xRot);
        applyCamelAttributeDefaults();
    }

    private void applyCamelAttributeDefaults() {
        // Vanilla camel attribute defaults.
        this.jumpStrength = 0.42F;
        this.movementSpeedAttribute = 0.09f;
        this.stepHeightAttribute = 1.5D;
    }

    /** Camel#isTamed, which CamelHusk inherits: a camel never carries FLAG_TAME and is always tamed. */
    @Override
    public boolean isTamed() {
        return true;
    }

    public void tickDashCooldown() {
        if (dashing && dashCooldown < 50 && (onGround || lastPredictedInLiquid || getRiding() != null)) {
            dashing = false;
        }
        if (dashCooldown > 0) {
            dashCooldown--;
        }
        firstTick = false;
    }

    public boolean isCamelSitting() {
        return lastPoseChangeTick < 0L;
    }

    /** Camel#getPoseTime at the client game time {@code gameTime}. */
    public long poseTime(long gameTime) {
        return gameTime - Math.abs(lastPoseChangeTick);
    }

    /** Camel#isInPoseTransition at the client game time {@code gameTime}. */
    public boolean isInPoseTransition(long gameTime) {
        return poseTime(gameTime) < (isCamelSitting() ? 40 : 52);
    }

    /** Camel#refuseToMove at the client game time {@code gameTime}. */
    public boolean refuseToMove(long gameTime) {
        return isCamelSitting() || isInPoseTransition(gameTime);
    }

    /** Camel#tickRidden: a controller with positive zza stands a fully seated camel up at {@code gameTime}. */
    public void tickRidden(boolean forward, long gameTime) {
        if (forward && isCamelSitting() && !isInPoseTransition(gameTime)) {
            actionPose = EntityPose.STANDING;
            lastPoseChangeTick = gameTime;
        }
    }

    /** Camel#standUpInstantly, which Camel#tick runs on the client for a sitting camel in water. */
    public void standUpInstantly(long gameTime) {
        actionPose = EntityPose.STANDING;
        lastPoseChangeTick = Math.max(0L, gameTime - 52L - 1L);
    }

    /** Camel#onSyncedDataUpdated: every DASH entry after the first tick sets a spent dash cooldown to 55. */
    public void setDashingFromMetadata(boolean dashing) {
        if (!firstTick && dashCooldown == 0) {
            dashCooldown = 55;
        }
        this.dashing = dashing;
    }
}
