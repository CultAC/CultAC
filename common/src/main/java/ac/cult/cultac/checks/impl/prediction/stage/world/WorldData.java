package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class WorldData {
    @Setter
    DesyncStatus lastOnGround;

    @Setter
    DesyncStatus inWater;

    @Setter
    DesyncStatus inLava;

    DesyncStatus touchingLava;

    @Setter
    DesyncStatus inFlowingLiquid;

    @Setter
    boolean mightBeInBlock;

    boolean canJump;
    boolean canFluidHop;
    boolean canFloatWhileRidden;
    boolean couldFloatWhileRidden;
    DesyncStatus climbingAtStart;

    @Setter
    DesyncStatus climbing;

    int numColliding;

    @Setter
    int numCollidingTickingAfterPlayer;

    /** The riders outside the player's riding trees among {@link #numCollidingTickingAfterPlayer}. */
    int numRidersTickingAfterPlayer;

    int numPushing;
    int numCarriedPushing;
    CarriedPush carriedPush;

    /** Whether Entity#push may move the player: not while it certainly carries a passenger (Entity#isVehicle). */
    boolean pushable;

    // Default model state identifies the supporting block, independent of its properties.
    @Setter
    int onBlock;

    BubbleColumnData bubbleColumn;
    PistonPushes pistonPushes;

    @Setter
    StuckSpeedData stuckSpeed;

    DesyncStatus honeySlide;
    DesyncStatus onHoneyBlock;

    @Setter
    MainSupportingBlockData mainSupportingBlockPos;

    SimpleCollisionBox fishingRodPulls;

    /** The x/z range of the pushes taken while the level loaded, carried into the first loaded travel; null if none. */
    SimpleCollisionBox loadingLevelPushes;

    /** The previous client tick's ground state, before this tick's pushes or riptide mark it unknown. */
    DesyncStatus carriedOnGround;

    /** Entity#isEyeInFluid(WATER) after this tick's Entity#baseTick. */
    boolean eyeInWater;

    /** LocalPlayer#isUnderWater during this tick: the previous tick's {@link #eyeInWater}. */
    boolean underwater;

    /** Entity#isSwimming after this tick's Entity#updateSwimming; true if the client may swim. */
    boolean swimming;

    /** Whether Entity#updateSwimming may go either way: a swimmer whose Entity#isInWater is unknown. */
    public boolean isSwimmingOpen() {
        return swimming && inWater.isDesync();
    }

    /** Entity#isSwimming for {@code candidate}: the state it travelled with while {@link #isSwimmingOpen()}. */
    public boolean isSwimming(PredVector candidate) {
        return candidate != null && candidate.getSwimming() != null ? candidate.getSwimming() : swimming;
    }

    /** A vehicle's Entity#isInWater after this tick's Entity#baseTick, from the blocks the client holds this tick. */
    boolean vehicleInWater;

    /** Whether LivingEntity#checkAutoSpinAttack may find a LivingEntity after this tick's travel. */
    boolean spinAttackTarget;

    /**
     * Whether that LivingEntity may be a RemotePlayer: Player#attack reaches Player#causeExtraKnockback only through
     * Entity#hurtOrSimulate, and RemotePlayer is the one LivingEntity whose hurtClient returns true.
     */
    boolean spinAttackPlayer;

    /** A shallow copy: carry contexts share its sub-objects and boxes, so none may be mutated in place. */
    public WorldData copy() {
        return new WorldData(
                lastOnGround,
                inWater,
                inLava,
                touchingLava,
                inFlowingLiquid,
                mightBeInBlock,
                canJump,
                canFluidHop,
                canFloatWhileRidden,
                couldFloatWhileRidden,
                climbingAtStart,
                climbing,
                numColliding,
                numCollidingTickingAfterPlayer,
                numRidersTickingAfterPlayer,
                numPushing,
                numCarriedPushing,
                carriedPush,
                pushable,
                onBlock,
                bubbleColumn,
                pistonPushes,
                stuckSpeed,
                honeySlide,
                onHoneyBlock,
                mainSupportingBlockPos,
                fishingRodPulls,
                loadingLevelPushes,
                carriedOnGround,
                eyeInWater,
                underwater,
                swimming,
                vehicleInWater,
                spinAttackTarget,
                spinAttackPlayer);
    }

    public boolean mustBeInLiquid() {
        return inWater.determinePessimistically() || inLava.determinePessimistically();
    }

    public boolean maybeInLiquid() {
        return inWater.determineOptimistically() || inLava.determineOptimistically();
    }
}
