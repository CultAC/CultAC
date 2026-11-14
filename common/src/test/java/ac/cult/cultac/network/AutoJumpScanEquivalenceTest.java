package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.checks.impl.prediction.pipeline.java.AutoJumpGeometryAccess;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The auto-jump look-ahead's first-match scan against the box list it tests, on randomized worlds. */
class AutoJumpScanEquivalenceTest {
    private static final int QUERIES = 1500;

    @Test
    void scanVisitsTheListedBoxesInOrderAndStopsAtTheFirstMatch() throws Exception {
        int nonEmpty = 0, matched = 0, missed = 0;
        for (ClientVersion client : List.of(ClientVersion.V_26_3, ClientVersion.V_1_21_4)) {
            for (long seed = 1; seed <= 3; seed++) {
                try (var services = new RecordConsumerServices();
                        var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
                    CultPlayer player = fixture.player;
                    CollisionGatheringEquivalenceTest.CollisionWorlds.populate(player, seed);
                    Field version = player.getClass().getDeclaredField("resolvedClientVersion");
                    version.setAccessible(true);
                    version.set(player, client);
                    var random = new SplittableRandom(seed * 31 + client.ordinal());
                    for (int i = 0; i < QUERIES; i++) {
                        SimpleCollisionBox query = region(random, player);
                        player.y = query.minY + random.nextDouble(-1, 1);
                        boolean snapshot = random.nextInt(8) == 0;
                        int[] counts = snapshot
                                ? player.compensatedWorld.withSelectionSnapshot(() -> compare(player, query, random))
                                : compare(player, query, random);
                        if (counts[0] > 0) nonEmpty++;
                        matched += counts[1];
                        missed += counts[2];
                    }
                }
            }
        }
        assertTrue(nonEmpty > QUERIES, "queries reaching boxes: " + nonEmpty);
        assertTrue(matched > QUERIES / 2 && missed > QUERIES / 2, "matched " + matched + " missed " + missed);
    }

    /** Box count, then how many of the Y-range tests matched and missed. */
    private static int[] compare(CultPlayer player, SimpleCollisionBox query, SplittableRandom random) {
        List<SimpleCollisionBox> listed = AutoJumpGeometryAccess.blockCollisions(player, query);
        List<Long> expected = new ArrayList<>();
        for (SimpleCollisionBox box : listed) {
            expected.add(Double.doubleToRawLongBits(box.minY));
            expected.add(Double.doubleToRawLongBits(box.maxY));
        }
        List<Long> visited = new ArrayList<>();
        assertFalse(AutoJumpGeometryAccess.anyBlockCollision(player, query, (minY, maxY) -> {
            visited.add(Double.doubleToRawLongBits(minY));
            visited.add(Double.doubleToRawLongBits(maxY));
            return false;
        }));
        assertEquals(expected, visited, () -> "query " + query);
        if (!listed.isEmpty()) {
            int stop = random.nextInt(listed.size());
            int[] seen = {0};
            assertTrue(AutoJumpGeometryAccess.anyBlockCollision(player, query, (minY, maxY) -> seen[0]++ == stop));
            assertEquals(stop + 1, seen[0], () -> "query " + query);
        }
        int matched = 0, missed = 0;
        for (int i = 0; i < 4; i++) {
            double low = query.minY + random.nextDouble(-1.5, 1.5), high = low + random.nextDouble(0, 0.6);
            double top = query.maxY + random.nextDouble(-1, 1), margin = random.nextDouble(0, 0.05);
            boolean list = false;
            for (SimpleCollisionBox box : listed) {
                for (double y : new double[] {box.minY, box.maxY}) {
                    if (y >= low && y <= high || Math.abs(y - top) <= margin) list = true;
                }
            }
            boolean scan = AutoJumpGeometryAccess.anyBlockCollision(
                    player,
                    query,
                    (minY, maxY) -> minY >= low && minY <= high
                            || Math.abs(minY - top) <= margin
                            || maxY >= low && maxY <= high
                            || Math.abs(maxY - top) <= margin);
            assertEquals(list, scan, () -> "query " + query);
            double height = low + 0.51F;
            boolean crossing = false;
            for (SimpleCollisionBox box : listed)
                if (box.minY < height + margin && box.maxY > height - margin) crossing = true;
            assertEquals(
                    crossing,
                    AutoJumpGeometryAccess.anyBlockCollision(
                            player, query, (minY, maxY) -> minY < height + margin && maxY > height - margin),
                    () -> "query " + query);
            if (list) matched++;
            else missed++;
        }
        return new int[] {listed.size(), matched, missed};
    }

    /** A collider query, widened in a third of cases to the look-ahead's reach of a few blocks. */
    private static SimpleCollisionBox region(SplittableRandom random, CultPlayer player) {
        SimpleCollisionBox query = CollisionGatheringEquivalenceTest.CollisionWorlds.randomQuery(random, player);
        if (random.nextInt(3) == 0) {
            double reach = random.nextDouble(0, 4);
            query.minX -= reach;
            query.minZ -= reach;
            query.maxX += reach;
            query.maxZ += reach;
        }
        return query;
    }
}
