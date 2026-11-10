package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedWorld.CachedChunk;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Chunk membership callbacks visit only the reloaded column's entities, yet keep the full-scan order: sections in
 * packed SectionPos order, then section entry order, each appended to EntityTickList as it enters.
 */
class JavaEntityChunkIndexTest {
    private static final int[] COLUMNS_X = {-1, 0, 1, 2};
    private static final int[] COLUMNS_Z = {-1, 0, 1};

    /** Random adds, section moves, removals and column unloads/reloads match a scan of every tracked entity. */
    @Test
    void columnCallbacksMatchAFullScan() throws Exception {
        for (long seed = 1; seed <= 6; seed++) {
            try (var f = new JavaTickFixture()) {
                new Scenario(f, new Random(seed)).run(1_500);
            }
        }
    }

    /** Unrelated columns cost nothing per tracked entity, and a reload appends a dense column in one pass. */
    @Test
    void columnCallbacksDoNotScaleWithTrackedEntities() throws Exception {
        try (var f = new JavaTickFixture()) {
            var world = f.player.compensatedWorld;
            var entities = f.player.compensatedEntities;
            CachedChunk column = world.getChunk(1, 1);
            for (int i = 0; i < 10_000; i++)
                entities.addEntity(1_000 + i, EntityTypeIds.ARMOR_STAND, new Vec3(20.5, 80, 20.5), 0, 0, 0);
            for (int i = 0; i < 10_000; i++)
                entities.addEntity(20_000 + i, EntityTypeIds.ARMOR_STAND, new Vec3(4.5, 80, 4.5), 0, 0, 0);
            long unrelated = Long.MAX_VALUE, reload = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long start = System.nanoTime();
                for (int i = 0; i < 10_000; i++) entities.onClientChunkTicking(40 + (i & 7), 40, (i & 8) == 0);
                unrelated = Math.min(unrelated, System.nanoTime() - start);
                world.removeChunkLater(1, 1, 0);
                start = System.nanoTime();
                world.putChunk(1, 1, column);
                reload = Math.min(reload, System.nanoTime() - start);
            }
            PacketEntity last = entities.getEntity(1_000 + 9_999);
            assertTrue(last.getClientTickOrder() > entities.getEntity(20_000).getClientTickOrder());
            // A scan of all 20,000 per callback takes seconds; a List#remove per entering entity ~60 ms.
            assertTrue(unrelated < 50_000_000L, "10,000 unrelated callbacks took " + unrelated + " ns");
            assertTrue(reload < 20_000_000L, "a 10,000-entity reload took " + reload + " ns");
        }
    }

    private static final class Scenario {
        private final JavaTickFixture f;
        private final Random random;
        private final CompensatedEntities entities;
        private final Map<Long, CachedChunk> saved = new HashMap<>();
        private final Set<Long> loaded = new HashSet<>();
        private final Map<Integer, Model> models = new LinkedHashMap<>();
        private final List<Model> addOrder = new ArrayList<>();
        private long nextOrder = 1, nextSection;
        private int nextId = 100;

        Scenario(JavaTickFixture f, Random random) {
            this.f = f;
            this.random = random;
            this.entities = f.player.compensatedEntities;
            for (int x : COLUMNS_X)
                for (int z : COLUMNS_Z) if (f.player.compensatedWorld.isChunkLoaded(x, z)) loaded.add(key(x, z));
        }

        void run(int steps) throws Exception {
            for (int step = 0; step < steps; step++) {
                int op = random.nextInt(10);
                if (op < 3 || models.size() < 8) add();
                else if (op < 6) move();
                else if (op < 7) remove();
                else toggleColumn();
                verify(step);
            }
        }

        private void add() {
            if (models.size() >= 60) return;
            int id = nextId++;
            boolean player = random.nextInt(12) == 0;
            entities.addEntity(id, player ? EntityTypeIds.PLAYER : EntityTypeIds.ZOMBIE, randomPosition(), 0, 0, 0);
            Model model = new Model(id, player);
            models.put(id, model);
            addOrder.add(model);
            moved(model);
        }

        private void move() {
            Model model = pick();
            PacketEntity entity = entities.getEntity(model.id);
            Vec3 at = entity.clientPhysicalPosition, to = randomPosition();
            entity.setPositionRaw(
                    entity.getExactMovementCollisionBox().copy().offset(to.x - at.x, to.y - at.y, to.z - at.z));
            moved(model);
        }

        private void remove() {
            Model model = pick();
            entities.removeEntity(model.id);
            models.remove(model.id);
            addOrder.remove(model);
        }

        private void toggleColumn() {
            int x = COLUMNS_X[random.nextInt(COLUMNS_X.length)], z = COLUMNS_Z[random.nextInt(COLUMNS_Z.length)];
            var world = f.player.compensatedWorld;
            long key = key(x, z);
            if (loaded.remove(key)) {
                saved.putIfAbsent(key, world.getChunk(x, z));
                world.removeChunkLater(x, z, 0);
                chunk(x, z, false);
            } else {
                loaded.add(key);
                CachedChunk chunk = saved.get(key);
                if (chunk == null) world.ensureValidationChunkLoaded(x, z);
                else world.putChunk(x, z, chunk);
                chunk(x, z, true);
            }
        }

        /** ClientEntityTickOrder#move: a new section takes the next entry; the chunk decides ticking. */
        private void moved(Model model) {
            Vec3 at = entities.getEntity(model.id).clientPhysicalPosition;
            int x = CultMath.floor(at.x) >> 4, y = CultMath.floor(at.y) >> 4, z = CultMath.floor(at.z) >> 4;
            if (!model.positioned || x != model.x || y != model.y || z != model.z) {
                model.x = x;
                model.y = y;
                model.z = z;
                model.positioned = true;
                model.entered = nextSection++;
            }
            membership(model, model.player || loaded.contains(key(x, z)));
        }

        /** The full scan: every tracked non-player in the column, entering in packed Y then section entry order. */
        private void chunk(int x, int z, boolean ticking) {
            List<Model> entering = new ArrayList<>();
            for (Model model : models.values()) {
                if (model.player || !model.positioned || model.x != x || model.z != z) continue;
                if (!ticking) membership(model, false);
                else if (!model.ticking) entering.add(model);
            }
            entering.sort(Comparator.comparingInt((Model model) -> model.y & 0xFFFFF)
                    .thenComparingLong(model -> model.entered));
            for (Model model : entering) membership(model, true);
        }

        /** CompensatedEntities#appendEntityToClientTickOrder per entering entity. */
        private void membership(Model model, boolean ticking) {
            if (ticking == model.ticking) return;
            model.ticking = ticking;
            if (!ticking) {
                model.order = Long.MAX_VALUE;
                return;
            }
            model.order = nextOrder++;
            if (addOrder.getLast() != model) {
                addOrder.remove(model);
                addOrder.add(model);
            }
        }

        private void verify(int step) throws Exception {
            List<Integer> expectedOrder = new ArrayList<>(), actualOrder = new ArrayList<>();
            List<Integer> expectedIdle = new ArrayList<>(), actualIdle = new ArrayList<>();
            List<Model> byOrder = new ArrayList<>(models.values());
            byOrder.sort(Comparator.comparingLong(model -> model.order));
            for (Model model : byOrder) (model.ticking ? expectedOrder : expectedIdle).add(model.id);
            List<PacketEntity> actual = new ArrayList<>();
            for (Model model : models.values()) actual.add(entities.getEntity(model.id));
            actual.sort(Comparator.comparingLong(PacketEntity::getClientTickOrder));
            for (PacketEntity entity : actual)
                (entity.getClientTickOrder() == Long.MAX_VALUE ? actualIdle : actualOrder).add(entity.getEntityId());
            assertEquals(expectedOrder, actualOrder, "tick order at step " + step);
            assertEquals(expectedIdle, actualIdle, "unticked entities at step " + step);
            List<Integer> expectedAdd = new ArrayList<>(), actualAdd = new ArrayList<>();
            for (Model model : addOrder) expectedAdd.add(model.id);
            Field field = CompensatedEntities.class.getDeclaredField("addOrder");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<PacketEntity> add = (List<PacketEntity>) field.get(entities);
            for (PacketEntity entity : add) actualAdd.add(entity.getEntityId());
            assertEquals(expectedAdd, actualAdd, "add order at step " + step);
            List<Integer> ticked = new ArrayList<>();
            entities.tickClientEntities(entity -> {
                if (models.containsKey(entity.getEntityId())) ticked.add(entity.getEntityId());
            });
            assertEquals(expectedOrder, ticked, "ticked entities at step " + step);
        }

        private Model pick() {
            List<Model> all = new ArrayList<>(models.values());
            return all.get(random.nextInt(all.size()));
        }

        private Vec3 randomPosition() {
            int x = COLUMNS_X[random.nextInt(COLUMNS_X.length)], z = COLUMNS_Z[random.nextInt(COLUMNS_Z.length)];
            // Sections on both sides of SectionPos's packed Y sign, a few entities per section.
            double y = (random.nextInt(6) - 2) * 16 + random.nextInt(16) + 0.5;
            return new Vec3(x * 16 + random.nextInt(16) + 0.5, y, z * 16 + random.nextInt(16) + 0.5);
        }

        private static long key(int x, int z) {
            return (long) x << 32 | z & 0xFFFFFFFFL;
        }
    }

    private static final class Model {
        final int id;
        final boolean player;
        int x, y, z;
        long entered, order = Long.MAX_VALUE;
        boolean positioned, ticking;

        Model(int id, boolean player) {
            this.id = id;
            this.player = player;
        }
    }
}
