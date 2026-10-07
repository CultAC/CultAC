package ac.cult.cultac.utils.data;

import ac.cult.blocksim.data.BlockFamilies;
import ac.cult.blocksim.data.BlockTags;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;
import java.util.Objects;
import lombok.Getter;

public class ShulkerData {
    public static final int BLOCK_ANIMATION_TICKS = 10;
    public static final int MAX_ENTITY_ANIMATION_TICKS = 20;

    public final int lastTransactionSent;

    @Getter
    private final boolean isClosing;

    private final int animationTicks;

    // Keep track of one of these two things, so we can remove this later
    public PacketEntity entity = null;
    public BlockPos blockPos = null;

    // Calculate if the player has no-push, and when to end the possibility of applying piston
    private int ticksOfOpeningClosing = 0;

    public ShulkerData(BlockPos position, int lastTransactionSent, boolean isClosing) {
        this(position, lastTransactionSent, isClosing, BLOCK_ANIMATION_TICKS);
    }

    public ShulkerData(BlockPos position, int lastTransactionSent, boolean isClosing, int animationTicks) {
        this.lastTransactionSent = lastTransactionSent;
        this.isClosing = isClosing;
        this.animationTicks = animationTicks;
        this.blockPos = position;
    }

    public ShulkerData(PacketEntity entity, int lastTransactionSent, boolean isClosing) {
        this(entity, lastTransactionSent, isClosing, MAX_ENTITY_ANIMATION_TICKS);
    }

    public ShulkerData(PacketEntity entity, int lastTransactionSent, boolean isClosing, int animationTicks) {
        this.lastTransactionSent = lastTransactionSent;
        this.isClosing = isClosing;
        this.animationTicks = animationTicks;
        this.entity = entity;
    }

    public Direction getFacing(CultPlayer player) {
        if (blockPos != null) {
            int state = player.compensatedWorld.getBlockStateIdAt(blockPos);
            if (BlockTags.SHULKER_BOXES.test(state) || BlockFamilies.SHULKER_BOX.test(state)) {
                return ClientBlockProperties.facing(state);
            }
        } else if (entity instanceof PacketEntityShulker) {
            return ((PacketEntityShulker) entity).facing.getOppositeFace();
        }
        return Direction.UP; // default state
    }

    public boolean canPushEntities() {
        // MCP-Reborn ShulkerBoxBlockEntity#updateAnimation only calls
        // moveCollidedEntities from the OPENING branch. Shulker#onPeekAmountChange
        // similarly only moves entities when the physical peek amount increases.
        return !isClosing && isAnimationActive();
    }

    public boolean isAnimationActive() {
        return ticksOfOpeningClosing < animationTicks;
    }

    public boolean tickIfGuaranteedFinished() {
        return ++ticksOfOpeningClosing >= animationTicks;
    }

    public SimpleCollisionBox getCollision() {
        if (blockPos != null) {
            return new SimpleCollisionBox(blockPos);
        }
        return entity.getPossibleMovementCollisionBoxes();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShulkerData that = (ShulkerData) o;
        return Objects.equals(entity, that.entity) && Objects.equals(blockPos, that.blockPos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entity, blockPos);
    }
}
