package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

/** Client-visible state used by AbstractNautilus#travelRidden. */
public final class PacketEntityNautilus extends PacketEntityTrackXRot {
    public boolean hasSaddle;
    public boolean isTame;
    public boolean dashing;
    public int dashCooldown;
    /** movementSpeed's value, rounded to a float as AbstractNautilus#getRiddenSpeed reads it. */
    public double movementSpeedAttribute = 1.0D;

    public final ClientMovementSpeed movementSpeed = new ClientMovementSpeed(1.0D);
    public double pendingJumpScale;
    public double nextPendingJumpScale;
    // Entity#firstTick: true until the nautilus's first client tick.
    private boolean firstTick = true;

    public PacketEntityNautilus(CultPlayer player, int entityId, int type, double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z, xRot);
        this.stepHeightAttribute = 1.0D;
        // ZombieNautilus#createAttributes overrides AbstractNautilus's 1.0 base with (double) 1.1F.
        if (EntityTypeUtil.isType(type, "zombie_nautilus")) {
            movementSpeed.defaultBase(1.1F);
            movementSpeedAttribute = 1.1F;
        }
    }

    @Override
    public ClientMovementSpeed riddenMovementSpeed() {
        return movementSpeed;
    }

    @Override
    public void refreshRiddenMovementSpeed() {
        movementSpeedAttribute = (float) movementSpeed.value();
    }

    /** AbstractNautilus#tick's dash tail, after Entity#baseTick has cleared firstTick. */
    public void tickDashCooldown() {
        firstTick = false;
        if (dashing && dashCooldown < 35) {
            setDashing(false);
        }
        if (dashCooldown > 0) {
            dashCooldown--;
        }
    }

    /**
     * A START_RIDING_JUMP the client sent after this nautilus's tick arms its next one. LocalPlayer#aiStep releases the
     * jump in ClientLevel#tickPassenger, after the root's whole tick, so AbstractNautilus#onPlayerJump reads the
     * cooldown tickDashCooldown left this tick.
     */
    public void tickPendingJump() {
        if (nextPendingJumpScale > 0.0D) {
            if (hasSaddle && dashCooldown <= 0) pendingJumpScale = nextPendingJumpScale;
            nextPendingJumpScale = 0.0D;
        }
    }

    /** AbstractNautilus#executeRidersJump's cooldown and DASH. */
    public void executeRidersJump() {
        dashCooldown = 40;
        setDashing(true);
    }

    /** AbstractNautilus#setDashing: SynchedEntityData#set calls onSyncedDataUpdated only when DASH changes. */
    public void setDashing(boolean dashing) {
        if (this.dashing == dashing) return;
        this.dashing = dashing;
        onDashUpdated();
    }

    /** SynchedEntityData#assignValues calls onSyncedDataUpdated for every DASH entry it receives, changed or not. */
    public void setDashingFromMetadata(boolean dashing) {
        this.dashing = dashing;
        onDashUpdated();
    }

    /** AbstractNautilus#onSyncedDataUpdated: a DASH update after the first tick sets a spent cooldown to 40. */
    private void onDashUpdated() {
        if (!firstTick && dashCooldown == 0) {
            dashCooldown = 40;
        }
    }
}
