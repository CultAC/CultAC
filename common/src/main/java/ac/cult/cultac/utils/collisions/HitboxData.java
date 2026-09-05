package ac.cult.cultac.utils.collisions;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;

public final class HitboxData {
    private HitboxData() {
    }

    public static CollisionBox getBlockHitbox(CultPlayer player, Material heldItem, BlockData block, int x, int y, int z) {
        if (player == null || player.compensatedWorld == null || block == null) {
            return NoCollisionBox.INSTANCE;
        }

        BlockState state = block instanceof CraftBlockData craftBlockData
                ? craftBlockData.getState()
                : player.compensatedWorld.getBlockStateAt(new BlockPos(x, y, z));

        // Selection shapes may query neighboring blocks; pass Cult's compensated world, never the live Bukkit world.
        return ClientBlockShapes.visual(player, state, block, x, y, z);
    }
}
