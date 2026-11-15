package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.lists.StableIndexSort;
import ac.cult.cultac.utils.math.Vec3;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * JavaBlockEntityPass#queryOrder's relation, Level#getEntities's section then section-entry order between in-pass
 * positions, is a strict total order over tracked actors and the local player, so its merge sort lists the actors
 * exactly as the former insertion sort did.
 */
class JavaBlockEntityQueryOrderTest {
    @Test
    void theActorQueryOrderIsTotalAndSortsAsAnInsertionSortDid() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            Random random = new Random(11);
            int count = 48;
            PacketEntity[] actors = new PacketEntity[count];
            for (int i = 0; i < count; i++) {
                entities.addEntity(500 + i, EntityTypeIds.ZOMBIE, position(random), 0, 0, 0);
                actors[i] = entities.getEntity(500 + i);
            }
            for (int round = 0; round < 12; round++) {
                // Some actors change section as the round moves them; the rest keep their section entries.
                for (int i = 0; i < 6; i++) {
                    PacketEntity entity = actors[random.nextInt(count)];
                    Vec3 at = entity.clientPhysicalPosition, to = position(random);
                    entity.setPositionRaw(entity.getExactMovementCollisionBox()
                            .copy()
                            .offset(to.x - at.x, to.y - at.y, to.z - at.z));
                }
                entities.observeLocalPlayerPosition(position(random));
                Vec3[] at = new Vec3[count];
                for (int i = 0; i < count; i++)
                    at[i] = random.nextBoolean() ? position(random) : actors[i].clientPhysicalPosition;
                Vec3 local = position(random);
                int self = count;
                StableIndexSort.Precedes precedes = (a, b) -> {
                    if (a == self || b == self) {
                        int remote = a == self ? b : a;
                        boolean localFirst =
                                entities.localPlayerPrecedesInEntityQuery(actors[remote], local, at[remote], null, 0);
                        return a == self == localFirst;
                    }
                    return entities.precedesInEntityQuery(actors[a], at[a], actors[b], at[b]);
                };
                for (int a = 0; a <= count; a++) {
                    for (int b = 0; b <= count; b++) {
                        if (a == b) continue;
                        assertTrue(precedes.test(a, b) != precedes.test(b, a), a + " and " + b + " must be ordered");
                        if (!precedes.test(a, b)) continue;
                        for (int c = 0; c <= count; c++)
                            if (c != a && c != b && precedes.test(b, c))
                                assertTrue(precedes.test(a, c), a + " < " + b + " < " + c);
                    }
                }
                for (int trial = 0; trial < 20; trial++) {
                    int[] order = new int[count + 1];
                    for (int i = 0; i <= count; i++) order[i] = i;
                    for (int i = count; i > 0; i--) {
                        int j = random.nextInt(i + 1), swap = order[i];
                        order[i] = order[j];
                        order[j] = swap;
                    }
                    int[] insertion = order.clone();
                    for (int i = 1; i < insertion.length; i++) {
                        int actor = insertion[i];
                        int j = i - 1;
                        while (j >= 0 && precedes.test(actor, insertion[j])) {
                            insertion[j + 1] = insertion[j];
                            j--;
                        }
                        insertion[j + 1] = actor;
                    }
                    int[] merged = order.clone();
                    StableIndexSort.sort(merged, new int[merged.length], merged.length, precedes);
                    assertArrayEquals(insertion, merged);
                }
            }
        }
    }

    /** Within two blocks of a few section corners, on both sides of the packed coordinates' sign. */
    private static Vec3 position(Random random) {
        double x = (random.nextInt(3) - 1) * 16 + random.nextDouble() * 4 - 2;
        double y = (random.nextInt(3) - 1) * 16 + 64 + random.nextDouble() * 4 - 2;
        double z = (random.nextInt(3) - 1) * 16 + random.nextDouble() * 4 - 2;
        return new Vec3(x, y, z);
    }
}
