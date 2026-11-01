package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntitySelf;
import ac.cult.cultac.utils.latency.CompensatedCameraEntity;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** CompensatedCameraEntity#isSelf against the loop over its possibilities, through the production write paths. */
class CameraIsSelfParityTest {
    private static final int STEPS = 4_000;

    @Test
    void isSelfMatchesTheLoopOverEveryWriteSequence() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            CompensatedCameraEntity camera = player.cameraEntity;
            int y = JavaTickFixture.FLOOR_Y;
            player.compensatedEntities.addEntity(70, EntityTypeIds.ZOMBIE, new Vec3(1, y, 1), 0, 0, 0);
            player.compensatedEntities.addEntity(71, EntityTypeIds.PIG, new Vec3(2, y, 1), 0, 0, 0);
            int[] cameraIds = {70, 71, player.entityID, 72};
            Random random = new Random(11);
            int external = 0;
            int several = 0;
            int severalSelf = 0;
            int staleSelf = 0;
            assertParity(camera, player);
            for (int step = 0; step < STEPS; step++) {
                int op = random.nextInt(10);
                if (op < 4) {
                    camera.onSetCamera(cameraIds[random.nextInt(cameraIds.length)]);
                } else if (op < 7) {
                    answer(player, player.lastTransactionReceived.get() + 1);
                } else if (op == 7) {
                    answer(player, player.lastTransactionSent.get() + 1);
                } else if (op == 8) {
                    camera.reset();
                } else {
                    player.compensatedEntities.playerEntity =
                            new PacketEntitySelf(player, player.compensatedEntities.playerEntity);
                }
                List<PacketEntity> possibilities = assertParity(camera, player);
                PacketEntity self = player.compensatedEntities.getSelf();
                if (possibilities.size() > 1) {
                    several++;
                    if (loopIsSelf(self, possibilities)) severalSelf++;
                } else if (possibilities.getFirst() != self) {
                    if (possibilities.getFirst() instanceof PacketEntitySelf) staleSelf++;
                    else external++;
                }
            }
            assertTrue(
                    external > 0 && several > 0 && severalSelf > 0 && staleSelf > 0,
                    external + " " + several + " " + severalSelf + " " + staleSelf);
        }
    }

    private static List<PacketEntity> assertParity(CompensatedCameraEntity camera, CultPlayer player) {
        List<PacketEntity> possibilities = camera.getPossibilities();
        assertEquals(loopIsSelf(player.compensatedEntities.getSelf(), possibilities), camera.isSelf());
        return possibilities;
    }

    private static void answer(CultPlayer player, int transaction) {
        if (transaction > player.lastTransactionSent.get() + 1) return;
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    // isSelf by definition: every possibility is the local player.
    private static boolean loopIsSelf(PacketEntity self, List<PacketEntity> possibilities) {
        for (PacketEntity entity : possibilities) if (entity != self) return false;
        return true;
    }
}
