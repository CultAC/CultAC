package ac.cult.cultac.network;

import static ac.cult.cultac.network.ClientMiningStateEquivalenceTest.SCALES;
import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The player box overloads that write into a reused box against the ones that return a fresh box. */
class PlayerBoxIntoEquivalenceTest {
    @Test
    void reusedBoxesMatchFreshPlayerBoxes() throws Exception {
        try (var f = new JavaTickFixture()) {
            CultPlayer player = f.player;
            Random random = new Random(7);
            Pose[] poses = Pose.values();
            var out = new SimpleCollisionBox();
            for (int riding = 0; riding < 2; riding++) {
                if (riding == 1) {
                    Vec3 at = new Vec3(0.5, FLOOR_Y, 0.5);
                    player.compensatedEntities.addEntity(4242, EntityTypeIds.OAK_BOAT, at, 0, 0.0F, 0);
                    f.pongThrough(player.lastTransactionSent.get());
                    f.startRiding(4242, 1, at, at, 0.0F, 0.0F);
                    f.pongThrough(player.lastTransactionSent.get());
                    assertTrue(player.compensatedEntities.getSelf().getRiding() != null, "riding");
                }
                for (int i = 0; i < 20_000; i++) {
                    double x = ClientMiningStateEquivalenceTest.coordinate(random, 0.0), y = ClientMiningStateEquivalenceTest.coordinate(random, FLOOR_Y), z = ClientMiningStateEquivalenceTest.coordinate(random, 0.0);
                    player.pose = poses[random.nextInt(poses.length)];
                    player.dimensionScale = random.nextInt(3) == 0 ? SCALES[random.nextInt(SCALES.length)] : 1.0F;
                    ClientMiningStateEquivalenceTest.poison(out, random);
                    var fresh = GetBoundingBox.getCollisionBoxForPlayer(player, x, y, z);
                    assertTrue(out == GetBoundingBox.getCollisionBoxForPlayer(out, player, x, y, z));
                    assertTrue(ClientMiningStateEquivalenceTest.same(fresh, out), riding + " collision " + fresh + " vs " + out);
                    ClientMiningStateEquivalenceTest.poison(out, random);
                    fresh = GetBoundingBox.getPlayerBoundingBox(player, x, y, z);
                    assertTrue(out == GetBoundingBox.getPlayerBoundingBox(out, player, x, y, z));
                    assertTrue(ClientMiningStateEquivalenceTest.same(fresh, out), riding + " player " + fresh + " vs " + out);
                }
            }
        }
    }
}