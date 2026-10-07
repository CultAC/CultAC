package ac.cult.cultac.utils.collisions;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.CollisionBox;

public final class CollisionData {
    private static final CollisionData INSTANCE = new CollisionData();

    private CollisionData() {}

    public static CollisionData getData(BlockDefinition material) {
        return INSTANCE;
    }

    public CollisionBox getMovementCollisionBox(CultPlayer player, ClientVersion version, int state) {
        return getMovementCollisionBox(player, version, state, 0, 0, 0);
    }

    public CollisionBox getMovementCollisionBox(
            CultPlayer player, ClientVersion version, int state, int x, int y, int z) {
        return ClientBlockShapes.movement(player, state, x, y, z);
    }
}
