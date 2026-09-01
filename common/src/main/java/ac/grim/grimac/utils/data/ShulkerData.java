package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.data.packetentity.PacketEntityShulker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.BlockFace;
import ac.grim.grimac.utils.nmsutil.NmsBlockTags;
import lombok.Getter;

import java.util.Objects;

public class ShulkerData {
    public static final int BLOCK_ANIMATION_TICKS = 10;
    public static final int MAX_ENTITY_ANIMATION_TICKS = 20;

    public final int lastTransactionSent;
    @Getter private final boolean isClosing;
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

    public BlockFace getFacing(GrimPlayer player) {
        if (blockPos != null) {
            BlockState state = player.compensatedWorld.getBlockStateAt(blockPos);
            if (NmsBlockTags.isShulkerBox(state)) {
                return NmsBlockTags.getFacing(state);
            }
        } else if (entity instanceof PacketEntityShulker) {
            return ((PacketEntityShulker) entity).facing.getOppositeFace();
        }
        return BlockFace.UP; // default state
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
