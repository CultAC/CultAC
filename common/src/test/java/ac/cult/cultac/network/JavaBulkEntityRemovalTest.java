package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedFireworks;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * ClientPacketListener#handleRemoveEntities removes each id in packet order. CompensatedEntities#removeEntities and
 * CompensatedFireworks#removeFireworks, which prune their lists once per packet, leave every entity, riding link,
 * tick order and rocket exactly as one removal per id does.
 */
class JavaBulkEntityRemovalTest {
    private static final int[] COLUMNS_X = {-1, 0, 1, 2};
    private static final int[] COLUMNS_Z = {-1, 0, 1};

    @Test
    void aPacketsRemovalsLeaveTheStateOfOneRemovalPerId() throws Exception {
        for (long seed = 1; seed <= 8; seed++) {
            String batched, single;
            try (var f = new JavaTickFixture()) {
                List<Integer> removed = build(f, new Random(seed));
                sent(f, removed);
                f.player.compensatedEntities.removeEntities(removed);
                f.player.compensatedFireworks.removeFireworks(removed);
                batched = state(f);
            }
            try (var f = new JavaTickFixture()) {
                List<Integer> removed = build(f, new Random(seed));
                sent(f, removed);
                for (int id : removed) {
                    f.player.compensatedEntities.removeEntity(id);
                    f.player.compensatedFireworks.removeFirework(id);
                }
                single = state(f);
            }
            assertEquals(single, batched, "seed " + seed);
        }
    }

    /** Tracked entities in shuffled add order, some riding and some rockets; returns a removal packet's ids. */
    private static List<Integer> build(JavaTickFixture f, Random random) throws Exception {
        var entities = f.player.compensatedEntities;
        var world = f.player.compensatedWorld;
        int[] types = {
            EntityTypeIds.ZOMBIE, EntityTypeIds.OAK_BOAT, EntityTypeIds.PLAYER, EntityTypeIds.FIREWORK_ROCKET
        };
        List<Integer> ids = new ArrayList<>();
        Map<Integer, Integer> typeOf = new HashMap<>();
        for (int i = 0; i < 80; i++) {
            int id = 100 + i, type = types[random.nextInt(types.length)];
            int x = COLUMNS_X[random.nextInt(COLUMNS_X.length)], z = COLUMNS_Z[random.nextInt(COLUMNS_Z.length)];
            Vec3 at = new Vec3(x * 16 + random.nextInt(16) + 0.5, 64 + random.nextInt(40), z * 16 + 8.5);
            entities.addEntity(id, type, at, 0, 0, 0);
            if (type == EntityTypeIds.FIREWORK_ROCKET) f.player.compensatedFireworks.addNewFirework(id);
            ids.add(id);
            typeOf.put(id, type);
            if (random.nextInt(10) == 0) {
                // A reloaded column re-enters its entities behind the newer ones.
                int cx = COLUMNS_X[random.nextInt(COLUMNS_X.length)], cz = COLUMNS_Z[random.nextInt(COLUMNS_Z.length)];
                CachedChunk column = world.getChunk(cx, cz);
                if (column != null) {
                    world.removeChunkLater(cx, cz, 0);
                    world.putChunk(cx, cz, column);
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            int vehicle = ids.get(random.nextInt(ids.size()));
            if (typeOf.get(vehicle) != EntityTypeIds.OAK_BOAT) continue;
            List<Integer> riders = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                int rider = ids.get(random.nextInt(ids.size()));
                if (typeOf.get(rider) == EntityTypeIds.ZOMBIE && !riders.contains(rider)) riders.add(rider);
            }
            f.setPassengers(vehicle, riders);
        }
        f.pongThrough(f.player.lastTransactionSent.get());
        List<Integer> removed = new ArrayList<>();
        for (int id : ids) if (random.nextInt(3) != 0) removed.add(id);
        removed.add(removed.isEmpty() ? 100 : removed.get(random.nextInt(removed.size())));
        removed.add(9_999);
        Collections.shuffle(removed, random);
        return removed;
    }

    /** handleRemoveEntities's send side for each id, before the removal task. */
    private static void sent(JavaTickFixture f, List<Integer> removed) {
        for (int id : removed) {
            f.player.compensatedEntities.onSentRemoval(id);
            f.player.compensatedEntities.vehicles.onSentRemoval(id);
            f.player.compensatedEntities.serverPositionsMap.remove(id);
        }
    }

    @SuppressWarnings("unchecked")
    private static String state(JavaTickFixture f) throws Exception {
        CompensatedEntities entities = f.player.compensatedEntities;
        StringBuilder out = new StringBuilder();
        out.append("add order ");
        for (PacketEntity entity : (List<PacketEntity>) field(CompensatedEntities.class, entities, "addOrder"))
            out.append(entity.getEntityId()).append(' ');
        Map<Integer, String> tracked = new TreeMap<>();
        for (var entry : entities.entityMap.int2ObjectEntrySet()) {
            PacketEntity entity = entry.getValue();
            List<Integer> riders = new ArrayList<>();
            for (PacketEntity rider : entity.passengers) riders.add(rider.getEntityId());
            tracked.put(
                    entry.getIntKey(),
                    "order=" + entity.getClientTickOrder() + " ticking=" + entities.isTicking(entity) + " riding="
                            + (entity.getRiding() == null ? -1 : entity.getRiding().getEntityId()) + " riders="
                            + riders);
        }
        out.append("\ntracked ").append(tracked);
        List<Integer> ticked = new ArrayList<>();
        entities.tickClientEntities(entity -> ticked.add(entity.getEntityId()));
        out.append("\nticked ").append(ticked);
        var rockets = field(CompensatedFireworks.class, f.player.compensatedFireworks, "activeFireworks");
        out.append("\nrockets ").append(rockets);
        var drops = (Map<?, ?>) field(CompensatedEntities.class, entities, "sentDrops");
        out.append("\ndrops ").append(new TreeMap<>(drops));
        return out.toString();
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
