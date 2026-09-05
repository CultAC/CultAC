package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import net.minecraft.core.BlockPos;
import org.bukkit.block.BlockFace;
import lombok.Getter;

import java.util.List;

public class PistonData implements TransactionOrder {
    private static final int LEGACY_CLIENT_VISIBLE_MOVING_PISTON_TICKS = 3;
    private static final int TICK_END_VISIBLE_PUSH_TICKS = 2;

    public final boolean isPush;
    public final boolean hasSlimeBlock;
    public final boolean hasHoneyBlock;
    public final BlockFace direction;
    public final int lastTransactionSent;
    private final boolean phaseWithClientTickEnd;

    // Calculate if the player has no-push, and when to end the possibility of applying piston
    public int ticksOfPistonBeingAlive = 0;

    // The movement areas from the last completed client block-entity tick.
    @Getter
    public List<SimpleCollisionBox> boxes;

    @Getter
    public final List<BlockPos> movingPositions;

    // MCP-Reborn PistonMovingBlockEntity#fixEntityWithinPistonBase can move an
    // entity in the piston-facing direction after a retracting source piston
    // first collided in the retract direction.
    @Getter
    public final List<SimpleCollisionBox> retractingSourceFixBoxes;

    public PistonData(BlockFace direction,
                      List<BlockPos> movingPositions,
                      List<SimpleCollisionBox> pushedBlocks,
                      List<SimpleCollisionBox> retractingSourceFixBoxes,
                      int lastTransactionSent,
                      boolean isPush,
                      boolean hasSlimeBlock,
                      boolean hasHoneyBlock,
                      boolean phaseWithClientTickEnd) {
        this.direction = direction;
        this.movingPositions = movingPositions;
        this.boxes = pushedBlocks;
        this.retractingSourceFixBoxes = retractingSourceFixBoxes;
        this.lastTransactionSent = lastTransactionSent;
        this.isPush = isPush;
        this.hasSlimeBlock = hasSlimeBlock;
        this.hasHoneyBlock = hasHoneyBlock;
        this.phaseWithClientTickEnd = phaseWithClientTickEnd;
    }

    public BlockFace getMovementDirection() {
        // MCP-Reborn PistonMovingBlockEntity#getMovementDirection returns the
        // piston facing while extending, and the opposite direction while retracting.
        return isPush ? direction : direction.getOppositeFace();
    }

    public boolean shouldCaptureMovementOnClientTickEnd() {
        return phaseWithClientTickEnd;
    }

    public void setBoxes(List<SimpleCollisionBox> boxes) {
        this.boxes = boxes;
    }

    public boolean canAffectMovement() {
        return !phaseWithClientTickEnd || ticksOfPistonBeingAlive > 0;
    }

    // MCP-Reborn PistonMovingBlockEntity#tick advances progress by 0.5F and calls
    // moveCollidedEntities before clamping progress to 1.0, so a moving piston can
    // shove entities for two client block-entity ticks. On clients with
    // ServerboundClientTickEndPacket, Cult advances this after tick-end, so those
    // two shoves apply to the next two movement packets. Older clients keep Cult's
    // legacy three-prediction window because they do not expose that boundary.
    public boolean tickIfGuaranteedFinished() {
        ticksOfPistonBeingAlive++;
        return phaseWithClientTickEnd
                ? ticksOfPistonBeingAlive > TICK_END_VISIBLE_PUSH_TICKS
                : ticksOfPistonBeingAlive >= LEGACY_CLIENT_VISIBLE_MOVING_PISTON_TICKS;
    }

    @Override
    public int getTransaction() {
        return lastTransactionSent;
    }
}
