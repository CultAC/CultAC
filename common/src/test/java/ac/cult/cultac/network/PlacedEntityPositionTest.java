package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/**
 * 1.21.11 {@code Entity#moveOrInterpolateTo} calls {@code setPos} for an entity whose {@code getInterpolation()} is
 * null, such as a falling block; only an {@code InterpolationHandler} carries local movement into its target.
 */
class PlacedEntityPositionTest {
    @Test
    void fallingBlockFollowsEveryServerPosition() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            JavaTickFixture.setField(CultPlayer.class, player, "resolvedClientVersion", ClientVersion.V_1_21_11);
            player.compensatedEntities.addEntity(10, EntityTypeIds.FALLING_BLOCK, new Vec3(0.5, 86, 0.5), 0, 0, 0);
            PacketEntity block = player.compensatedEntities.getEntity(10);

            block.onBundledPositionSyncTransaction(0.5, 85.9599609375, 0.5, player);
            tick(player, block, 3);
            assertEquals(85.9599609375, minY(block), 1e-9);

            double[] moves = {-2.35693359375, -2.547607421875, -2.5478515625};
            double y = 85.9599609375;
            for (double move : moves) {
                y += move;
                block.onFirstTransaction(false, true, 0.5, y, 0.5, player);
                block.onSecondTransaction();
                tick(player, block, 20);
                assertEquals(y, minY(block), 1e-9);
            }
        }
    }

    private static void tick(CultPlayer player, PacketEntity entity, int ticks) {
        for (int i = 0; i < ticks; i++) entity.onMovement(player, true);
    }

    private static double minY(PacketEntity entity) {
        SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
        return box.minY;
    }
}
