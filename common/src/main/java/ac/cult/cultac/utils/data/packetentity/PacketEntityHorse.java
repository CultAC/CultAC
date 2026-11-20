package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.integration.BedrockVehiclePredictionState;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;

public class PacketEntityHorse extends PacketEntityTrackXRot {
    public boolean isRearing = false;
    public boolean hasSaddle = false;
    public boolean isTame = false;
    public double jumpStrength = 0.7;
    public double nextHorseJump = 0;
    public double horseJump = 0;
    public boolean allowStandSliding = false;
    public boolean nextAllowStandSliding = false;
    /** AbstractHorse#standCounter: local and never synced, so metadata writes leave it running. */
    private int standCounter = 0;

    private boolean nextStandIfPossible = false;
    public float movementSpeedAttribute = 0.225f;
    public float standAnim = 0.0F;
    public float standAnimO = 0.0F;
    public long horseFlagsRevision;

    public PacketEntityHorse(CultPlayer player, int entityId, int type, double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z, xRot);

        if (EntityTypeUtil.isChestedHorseFamily(type)) {
            jumpStrength = 0.5;
            movementSpeedAttribute = 0.175f;
        }

        if (type == EntityTypeIds.ZOMBIE_HORSE || type == EntityTypeIds.SKELETON_HORSE) {
            movementSpeedAttribute = 0.2f;
        }
        // Geyser supplies this initial value when spawning a horse.
        if (player.isBedrockMovement()) jumpStrength = 0.5F;
    }

    /** AbstractHorse#isTamed: FLAG_TAME of DATA_ID_FLAGS. */
    public boolean isTamed() {
        return isTame;
    }

    @Override
    public void onMovement(CultPlayer player, boolean highBound) {
        super.onMovement(player, highBound);
        if (player.isBedrockMovement() && bedrockPrediction != null) {
            BedrockVehiclePredictionState.tickRemote(this);
        } else {
            tickStandAnimation();
        }
    }

    private void tickStandAnimation() {
        tickStandTail();
    }

    /**
     * MCP-Reborn AbstractHorse#tick after super.tick() (so after travel): count down standCounter, then update
     * standAnim from the single visible standing bit (DATA_ID_FLAGS 32, AbstractHorse#isStanding).
     */
    public void tickStandTail() {
        if (standCounter > 0 && --standCounter <= 0) {
            clearStanding();
        }

        standAnimO = standAnim;
        if (isRearing) {
            standAnim += (1.0F - standAnim) * 0.4F + 0.05F;
            if (standAnim > 1.0F) {
                standAnim = 1.0F;
            }
        } else {
            // AbstractHorse#tick clears allowStandSliding on every tick the horse is not standing.
            allowStandSliding = false;
            standAnim += (0.8F * standAnim * standAnim * standAnim - standAnim) * 0.6F - 0.05F;
            if (standAnim < 0.0F) {
                standAnim = 0.0F;
            }
        }
    }

    /** The riding jump LocalPlayer#aiStep would release this tick, for a vehicle that cannot jump after its tick. */
    public void dropRidingJump() {
        nextHorseJump = 0.0D;
        nextAllowStandSliding = false;
        nextStandIfPossible = false;
    }

    public void armClientRidingJumpStand() {
        // MCP-Reborn AbstractHorse#onPlayerJump calls standIfPossible() during the
        // passenger tick, after the root horse tick has already run. Stage it for
        // promoteClientRidingJumpStand() at the client tick end.
        nextStandIfPossible = true;
    }

    /** AbstractHorse#standIfPossible from the staged onPlayerJump, run after this tick's root tail. */
    public void promoteClientRidingJumpStand(boolean locallyTicked) {
        if (!nextStandIfPossible) return;
        nextStandIfPossible = false;
        // Camel#canPerformRearing and Llama#canPerformRearing are false; on the client, Entity#isEffectiveAi requires
        // local authority and Mob#isEffectiveAi requires !isNoAi().
        if (!locallyTicked || !canPerformRearing() || noAI) return;
        // AbstractHorse#setStanding(20).
        horseFlagsRevision++;
        isRearing = true;
        standCounter = 20;
    }

    private boolean canPerformRearing() {
        return !(this instanceof PacketEntityCamel)
                && type != EntityTypeIds.LLAMA
                && !EntityTypeUtil.isType(type, "trader_llama");
    }

    /** AbstractHorse#clearStanding. */
    private void clearStanding() {
        horseFlagsRevision++;
        isRearing = false;
        standCounter = 0;
    }

    public void setRearingFromMetadata(boolean rearing) {
        horseFlagsRevision++;
        // SynchedEntityData writes only the flag bit; the local standCounter keeps running, as in vanilla.
        isRearing = rearing;
    }
}
