package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import ac.cult.cultac.utils.debug.DebugText;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
public class WorldData implements DebugText.Recordable {
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

    /** The pushes LocalPlayer#moveTowardsClosestSpace may set this tick, one of which it sets; never empty. */
    List<BlockPush> blockPushes;

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

    StuckEdgeData sneak;
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

    /** A vehicle's Entity#isInWater after this tick's Entity#baseTick, from the blocks the client holds this tick. */
    boolean vehicleInWater;

    /** Whether LivingEntity#checkAutoSpinAttack may find a LivingEntity after this tick's travel. */
    boolean spinAttackTarget;

    /**
     * Whether that LivingEntity may be a RemotePlayer: Player#attack reaches Player#causeExtraKnockback only through
     * Entity#hurtOrSimulate, and RemotePlayer is the one LivingEntity whose hurtClient returns true.
     */
    boolean spinAttackPlayer;

    /** Every field, as the Cult Log prints them. A new field belongs here too; CultLogContentTest checks. */
    @Override
    public void appendTo(DebugText.Builder sb) {
        sb.append("WorldData(lastOnGround=").append(lastOnGround);
        sb.append(", inWater=").append(inWater);
        sb.append(", inLava=").append(inLava);
        sb.append(", touchingLava=").append(touchingLava);
        sb.append(", inFlowingLiquid=").append(inFlowingLiquid);
        sb.append(", mightBeInBlock=").append(mightBeInBlock);
        sb.append(", blockPushes=").append(blockPushes);
        sb.append(", canJump=").append(canJump);
        sb.append(", canFluidHop=").append(canFluidHop);
        sb.append(", canFloatWhileRidden=").append(canFloatWhileRidden);
        sb.append(", couldFloatWhileRidden=").append(couldFloatWhileRidden);
        sb.append(", climbingAtStart=").append(climbingAtStart);
        sb.append(", climbing=").append(climbing);
        sb.append(", numColliding=").append(numColliding);
        sb.append(", numCollidingTickingAfterPlayer=").append(numCollidingTickingAfterPlayer);
        sb.append(", numRidersTickingAfterPlayer=").append(numRidersTickingAfterPlayer);
        sb.append(", numPushing=").append(numPushing);
        sb.append(", numCarriedPushing=").append(numCarriedPushing);
        sb.append(", carriedPush=").append(carriedPush);
        sb.append(", pushable=").append(pushable);
        sb.append(", onBlock=").append(onBlock);
        sb.append(", sneak=").append(sneak);
        sb.append(", bubbleColumn=").append(bubbleColumn);
        sb.append(", pistonPushes=").append(pistonPushes);
        sb.append(", stuckSpeed=").append(stuckSpeed);
        sb.append(", honeySlide=").append(honeySlide);
        sb.append(", onHoneyBlock=").append(onHoneyBlock);
        sb.append(", mainSupportingBlockPos=").append(mainSupportingBlockPos);
        sb.append(", fishingRodPulls=").append(fishingRodPulls);
        sb.append(", loadingLevelPushes=").append(loadingLevelPushes);
        sb.append(", carriedOnGround=").append(carriedOnGround);
        sb.append(", eyeInWater=").append(eyeInWater);
        sb.append(", underwater=").append(underwater);
        sb.append(", swimming=").append(swimming);
        sb.append(", vehicleInWater=").append(vehicleInWater);
        sb.append(", spinAttackTarget=").append(spinAttackTarget);
        sb.append(", spinAttackPlayer=").append(spinAttackPlayer);
        sb.append(')');
    }

    @Override
    public String toString() {
        return DebugText.print(this);
    }

    /** A shallow copy: carry contexts share its sub-objects and boxes, so none may be mutated in place. */
    public WorldData copy() {
        return new WorldData(
                lastOnGround,
                inWater,
                inLava,
                touchingLava,
                inFlowingLiquid,
                mightBeInBlock,
                blockPushes,
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
                sneak,
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
