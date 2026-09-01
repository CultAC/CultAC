package ac.grim.grimac.utils.anticheat.update;

import ac.grim.grimac.network.protocol.util.SpigotConversionUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.HitboxData;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

public final class BlockBreak {
    public final BlockPos position;
    public final BlockFace face;
    public final int faceId;
    public final ServerboundPlayerActionPacket.Action action;
    public final int sequence;
    public final BlockState block;
    private final GrimPlayer player;
    @Getter
    private boolean cancelled;

    public BlockBreak(GrimPlayer player, BlockPos position, BlockFace face, int faceId, ServerboundPlayerActionPacket.Action action, int sequence, BlockState block) {
        this.player = player;
        this.position = position;
        this.face = face;
        this.faceId = faceId;
        this.action = action;
        this.sequence = sequence;
        this.block = block;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public SimpleCollisionBox getCombinedBox() {
        CollisionBox placedOn = HitboxData.getBlockHitbox(player, player.getInventory().getHeldItem().getType(), SpigotConversionUtil.fromNmsBlockState(block), position.getX(), position.getY(), position.getZ());

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        placedOn.downCast(boxes);

        SimpleCollisionBox combined = new SimpleCollisionBox(position.getX(), position.getY(), position.getZ());
        for (SimpleCollisionBox box : boxes) {
            double minX = Math.max(box.minX, combined.minX);
            double minY = Math.max(box.minY, combined.minY);
            double minZ = Math.max(box.minZ, combined.minZ);
            double maxX = Math.min(box.maxX, combined.maxX);
            double maxY = Math.min(box.maxY, combined.maxY);
            double maxZ = Math.min(box.maxZ, combined.maxZ);
            combined = new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        return combined;
    }
}
