package ac.cult.cultac.checks.impl.prediction.stage.world;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.CarriedPush;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
public class WorldData {
    @Setter
    DesyncStatus lastOnGround;

    /** Entity#isInWater after this tick's Entity#baseTick. */
    @Setter
    boolean inWater;

    /** Entity#isInLava after this tick's Entity#baseTick. */
    @Setter
    boolean inLava;

    /** LivingEntity#canStandOnFluid for the fluid at the actor's starting block, independently of body contact. */
    @Setter
    boolean canStandOnStartFluid;

    /** Whether that fluid update touched a fluid with a current. */
    @Setter
    boolean inFlowingLiquid;

    /** Cached at Entity#baseTick; later Entity#move callbacks may refresh the moved state's fluids. */
    @Setter
    double waterHeight;

    @Setter
    double lavaHeight;

    /**
     * Bedrock's contact on either of its smallest and tallest pose boxes, where the fields above need both; null for
     * Java, whose fields are the one exact Entity#getBoundingBox sample.
     */
    BedrockFluidBounds bedrockFluidBounds;

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

    StuckEdgeData sneak;
    BubbleColumnData bubbleColumn;
    PistonPushes pistonPushes;

    @Setter
    StuckSpeedData stuckSpeed;

    DesyncStatus honeySlide;
    DesyncStatus onHoneyBlock;

    @Setter
    MainSupportingBlockData mainSupportingBlockPos;


    /** The x/z range of the pushes taken while the level loaded, carried into the first loaded travel; null if none. */
    SimpleCollisionBox loadingLevelPushes;

    /** The previous client tick's ground state, before this tick's pushes or riptide mark it unknown. */
    DesyncStatus carriedOnGround;

    /** Entity#isEyeInFluid(WATER) after this tick's Entity#baseTick. */
    boolean eyeInWater;

    /** The cached eye contact Player#updateIsUnderwater reads before this tick's Entity#baseTick. */
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

    public void setSpinAttack(boolean target, boolean player) {
        spinAttackTarget = target;
        spinAttackPlayer = player;
    }

    /** Cached contact at this baseTick, before later reached move callbacks refresh it. */
    @Setter
    int eyeFluidContact;

    public WorldData(
            DesyncStatus lastOnGround,
            boolean inWater,
            boolean inLava,
            boolean inFlowingLiquid,
            BedrockFluidBounds bedrockFluidBounds,
            boolean mightBeInBlock,
            boolean canJump,
            boolean canFluidHop,
            boolean canFloatWhileRidden,
            boolean couldFloatWhileRidden,
            DesyncStatus climbingAtStart,
            DesyncStatus climbing,
            int numColliding,
            int numCollidingTickingAfterPlayer,
            int numRidersTickingAfterPlayer,
            int numPushing,
            int numCarriedPushing,
            CarriedPush carriedPush,
            boolean pushable,
            int onBlock,
            StuckEdgeData sneak,
            BubbleColumnData bubbleColumn,
            PistonPushes pistonPushes,
            StuckSpeedData stuckSpeed,
            DesyncStatus honeySlide,
            DesyncStatus onHoneyBlock,
            MainSupportingBlockData mainSupportingBlockPos,
            SimpleCollisionBox loadingLevelPushes,
            DesyncStatus carriedOnGround,
            boolean eyeInWater,
            boolean underwater,
            boolean swimming,
            boolean vehicleInWater,
            boolean spinAttackTarget,
            boolean spinAttackPlayer) {
        this.lastOnGround = lastOnGround;
        this.inWater = inWater;
        this.inLava = inLava;
        this.inFlowingLiquid = inFlowingLiquid;
        this.bedrockFluidBounds = bedrockFluidBounds;
        this.mightBeInBlock = mightBeInBlock;
        this.canJump = canJump;
        this.canFluidHop = canFluidHop;
        this.canFloatWhileRidden = canFloatWhileRidden;
        this.couldFloatWhileRidden = couldFloatWhileRidden;
        this.climbingAtStart = climbingAtStart;
        this.climbing = climbing;
        this.numColliding = numColliding;
        this.numCollidingTickingAfterPlayer = numCollidingTickingAfterPlayer;
        this.numRidersTickingAfterPlayer = numRidersTickingAfterPlayer;
        this.numPushing = numPushing;
        this.numCarriedPushing = numCarriedPushing;
        this.carriedPush = carriedPush;
        this.pushable = pushable;
        this.onBlock = onBlock;
        this.sneak = sneak;
        this.bubbleColumn = bubbleColumn;
        this.pistonPushes = pistonPushes;
        this.stuckSpeed = stuckSpeed;
        this.honeySlide = honeySlide;
        this.onHoneyBlock = onHoneyBlock;
        this.mainSupportingBlockPos = mainSupportingBlockPos;
        this.loadingLevelPushes = loadingLevelPushes;
        this.carriedOnGround = carriedOnGround;
        this.eyeInWater = eyeInWater;
        this.underwater = underwater;
        this.swimming = swimming;
        this.vehicleInWater = vehicleInWater;
        this.spinAttackTarget = spinAttackTarget;
        this.spinAttackPlayer = spinAttackPlayer;
        this.eyeFluidContact = ac.cult.cultac.utils.nmsutil.ClientFluidQueries.eyeContactFromWater(eyeInWater);
    }

    /** A shallow copy: carry contexts share its sub-objects and boxes, so none may be mutated in place. */
    public WorldData copy() {
        WorldData copy = new WorldData(
                lastOnGround,
                inWater,
                inLava,
                inFlowingLiquid,
                bedrockFluidBounds,
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
                sneak,
                bubbleColumn,
                pistonPushes,
                stuckSpeed,
                honeySlide,
                onHoneyBlock,
                mainSupportingBlockPos,
                loadingLevelPushes,
                carriedOnGround,
                eyeInWater,
                underwater,
                swimming,
                vehicleInWater,
                spinAttackTarget,
                spinAttackPlayer);
        copy.eyeFluidContact = eyeFluidContact;
        copy.waterHeight = waterHeight;
        copy.lavaHeight = lavaHeight;
        copy.canStandOnStartFluid = canStandOnStartFluid;
        return copy;
    }

    public boolean isInLiquid() {
        return inWater || inLava;
    }

    /** Liquid contact that selects fluid travel rather than the actor's air-travel path. */
    public boolean isInTravelLiquid() {
        return isInLiquid() && !canStandOnStartFluid;
    }

    /** Bedrock's contact on either pose box; for Java the exact contact. */
    public boolean mayBeInWater() {
        return bedrockFluidBounds == null ? inWater : bedrockFluidBounds.water();
    }

    public boolean mayBeInLava() {
        return bedrockFluidBounds == null ? inLava : bedrockFluidBounds.lava();
    }

    public boolean mayBeInFlowingLiquid() {
        return bedrockFluidBounds == null ? inFlowingLiquid : bedrockFluidBounds.flowing();
    }

    public boolean mayBeInLiquid() {
        return mayBeInWater() || mayBeInLava();
    }

    /** The water contacts a shared consumer branches on: Java's one exact contact, or both Bedrock bounds. */
    public List<Boolean> waterStates() {
        return states(inWater, mayBeInWater());
    }

    public List<Boolean> lavaStates() {
        return states(inLava, mayBeInLava());
    }

    private static List<Boolean> states(boolean certain, boolean possible) {
        return certain == possible ? (certain ? TRUE : FALSE) : EITHER;
    }

    private static final List<Boolean> TRUE = List.of(true);
    private static final List<Boolean> FALSE = List.of(false);
    private static final List<Boolean> EITHER = List.of(false, true);

    /** Bedrock's fluid contact on either of its pose boxes. */
    public record BedrockFluidBounds(boolean water, boolean lava, boolean flowing) {}
}
