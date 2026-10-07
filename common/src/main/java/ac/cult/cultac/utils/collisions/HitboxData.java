package ac.cult.cultac.utils.collisions;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;
import ac.cult.cultac.utils.collisions.datatypes.NoCollisionBox;

public final class HitboxData {
    private HitboxData() {}

    public static CollisionBox getBlockHitbox(CultPlayer player, int block, int x, int y, int z) {
        if (player == null || player.compensatedWorld == null || block < 0) {
            return NoCollisionBox.INSTANCE;
        }

        // Selection shapes may query neighboring blocks; pass Cult's compensated world, never the live Bukkit world.
        return ClientBlockShapes.visual(player, block, block, x, y, z);
    }
}
