package ac.grim.grimac.checks.impl.prediction.stage.world;

import ac.grim.grimac.checks.impl.prediction.DesyncStatus;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import org.bukkit.Material;
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
    boolean mightBeInBlock;
    boolean canJump;
    boolean canFluidHop;
    boolean canFloatWhileRidden;
    boolean couldFloatWhileRidden;
    DesyncStatus climbingAtStart;
    @Setter
    DesyncStatus climbing;
    int numColliding;
    int soulSandCount;
    @Setter
    DesyncStatus weirdFourteenFifteenLava;
    Material onBlock;
    StuckEdgeData sneak;
    BubbleColumnData bubbleColumn;
    PistonPushes pistonPushes;
    @Setter
    StuckSpeedData stuckSpeed;
    DesyncStatus honeySlide;
    DesyncStatus onHoneyBlock;
    MainSupportingBlockData mainSupportingBlockPos;
    SimpleCollisionBox fishingRodPulls;


    public boolean mustBeInLiquid() {
        return inWater.determinePessimistically() || inLava.determinePessimistically();
    }

    public boolean maybeInLiquid() {
        return inWater.determineOptimistically() || inLava.determineOptimistically();
    }
}
