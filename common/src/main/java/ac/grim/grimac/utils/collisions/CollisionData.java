package ac.grim.grimac.utils.collisions;

import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.CollisionBox;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

public final class CollisionData {
    private static final CollisionData INSTANCE = new CollisionData();

    private CollisionData() {
    }

    public static CollisionData getData(Material material) {
        return INSTANCE;
    }

    public CollisionBox getMovementCollisionBox(GrimPlayer player, ClientVersion version, BlockData state) {
        return getMovementCollisionBox(player, version, state, 0, 0, 0);
    }

    public CollisionBox getMovementCollisionBox(GrimPlayer player, ClientVersion version, BlockData state, int x, int y, int z) {
        return ClientBlockShapes.movement(player, state, x, y, z);
    }
}
