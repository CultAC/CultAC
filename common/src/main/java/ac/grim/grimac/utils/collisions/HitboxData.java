package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import ac.grim.grimac.utils.collisions.datatypes.NoCollisionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

public final class HitboxData {
    private HitboxData() {
    }

    public static CollisionBox getBlockHitbox(GrimPlayer player, Material heldItem, BlockData block, int x, int y, int z) {
        if (player == null || player.compensatedWorld == null || block == null) {
            return NoCollisionBox.INSTANCE;
        }

        BlockState state = block instanceof CraftBlockData craftBlockData
                ? craftBlockData.getState()
                : player.compensatedWorld.getBlockStateAt(new BlockPos(x, y, z));

        // Selection shapes may query neighboring blocks; pass Grim's compensated world, never the live Bukkit world.
        return ClientBlockShapes.visual(player, state, block, x, y, z);
    }
}
