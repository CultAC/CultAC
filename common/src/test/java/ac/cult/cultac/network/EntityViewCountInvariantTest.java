package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.latency.CompensatedEntities.EntityMap;
import ac.cult.cultac.utils.math.Vec3;
import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Each type view's member count equals a recount of the entity map after every mutation of a randomized sequence:
 * puts of new ids, replacements by the same and by another type, removals of present and absent ids, clears, the
 * view and boxed mutators that reach the counted paths, and the map mutators that throw.
 */
class EntityViewCountInvariantTest {
    private static final int IDS = 40;

    @Test
    void countsMatchARecountAfterEveryMutation() throws Exception {
        Method viewCount = EntityMap.class.getDeclaredMethod("viewCount", int.class);
        viewCount.setAccessible(true);
        Predicate<PacketEntity> unknown =
                e -> e.isBoat() || e.type == EntityTypeIds.SHULKER || e instanceof PacketEntityHappyGhast;
        Map<String, Predicate<PacketEntity>> accept = new LinkedHashMap<>();
        accept.put("PUSH_OR_COLLIDE", PacketEntity::mayPushOrCollide);
        accept.put("BOATS", PacketEntity::isBoat);
        accept.put("CAMELS", e -> e instanceof PacketEntityCamel);
        accept.put("POSE_COLLISION", e -> e.isBoat() || e.type == EntityTypeIds.SHULKER || e.isHappyGhast());
        accept.put("UNKNOWN_COLLISION", unknown);
        accept.put("COLLISION_OR_PLAYER", unknown.or(e -> e instanceof PacketEntityPlayer));
        int[] index = new int[accept.size()];
        List<Predicate<PacketEntity>> predicates = new ArrayList<>(accept.values());
        int v = 0;
        for (String name : accept.keySet()) {
            var field = EntityMap.class.getDeclaredField(name);
            field.setAccessible(true);
            index[v++] = field.getInt(null);
        }

        try (var f = new JavaTickFixture()) {
            Map<Integer, List<PacketEntity>> byType = pool(f.player.compensatedEntities);
            List<Integer> types = new ArrayList<>(byType.keySet());
            for (int type : new int[] {
                EntityTypeIds.OAK_BOAT,
                EntityTypeIds.SHULKER,
                EntityTypeIds.HAPPY_GHAST,
                EntityTypeIds.PLAYER,
                EntityTypes.defaults().byKey("minecraft:camel").id()
            }) {
                assertTrue(byType.containsKey(type), "no instance of type " + type);
            }
            int[] seen = new int[Op.values().length];
            int[] nonZero = new int[index.length];
            for (int seed = 0; seed < 4; seed++) {
                Random random = new Random(seed);
                EntityMap map = new EntityMap();
                for (int step = 0; step < 4000; step++) {
                    Op op = Op.values()[random.nextInt(Op.values().length)];
                    int id = random.nextInt(IDS);
                    PacketEntity present = map.get(id);
                    int type =
                            random.nextInt(4) == 0 ? EntityTypeIds.OAK_BOAT : types.get(random.nextInt(types.size()));
                    PacketEntity value = pick(byType, type, random);
                    switch (op) {
                        case PUT_NEW -> {
                            if (present != null) continue;
                            map.put(id, value);
                        }
                        case PUT_SAME_TYPE -> {
                            if (present == null) continue;
                            map.put(id, pick(byType, present.type, random));
                        }
                        case PUT_OTHER_TYPE -> {
                            if (present == null || value.type == present.type) continue;
                            map.put(id, value);
                        }
                        case REMOVE_PRESENT -> {
                            if (present == null) continue;
                            map.remove(id);
                        }
                        case REMOVE_ABSENT -> {
                            if (present != null) continue;
                            map.remove(id);
                        }
                        case CLEAR -> {
                            if (random.nextInt(20) != 0) continue;
                            map.clear();
                        }
                        case BOXED_PUT -> map.put(Integer.valueOf(id), value);
                        case BOXED_REMOVE -> map.remove(Integer.valueOf(id));
                        case KEY_SET_REMOVE -> map.keySet().remove(id);
                        case KEY_SET_CLEAR -> {
                            if (random.nextInt(20) != 0) continue;
                            map.keySet().clear();
                        }
                        case PUT_ALL -> {
                            Int2ObjectOpenHashMap<PacketEntity> more = new Int2ObjectOpenHashMap<>();
                            more.put(id, value);
                            more.put((id + 7) % IDS, pick(byType, types.get(random.nextInt(types.size())), random));
                            if (random.nextBoolean()) map.putAll(more);
                            else map.putAll(new HashMap<>(more));
                        }
                        case PUT_IF_ABSENT ->
                            assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent(id, value));
                        case REMOVE_VALUE ->
                            assertThrows(UnsupportedOperationException.class, () -> map.remove(id, present));
                        case REPLACE_VALUE ->
                            assertThrows(UnsupportedOperationException.class, () -> map.replace(id, present, value));
                        case REPLACE -> assertThrows(UnsupportedOperationException.class, () -> map.replace(id, value));
                        case COMPUTE_IF_ABSENT ->
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> map.computeIfAbsent(id, (IntFunction<PacketEntity>) k -> value));
                        case COMPUTE_IF_ABSENT_FUNCTION ->
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> map.computeIfAbsent(id, (Int2ObjectFunction<PacketEntity>) k -> value));
                        case COMPUTE_IF_PRESENT ->
                            assertThrows(
                                    UnsupportedOperationException.class,
                                    () -> map.computeIfPresent(id, (k, e) -> value));
                        case COMPUTE ->
                            assertThrows(UnsupportedOperationException.class, () -> map.compute(id, (k, e) -> value));
                        case MERGE ->
                            assertThrows(UnsupportedOperationException.class, () -> map.merge(id, value, (a, b) -> b));
                        case REPLACE_ALL ->
                            assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((k, e) -> value));
                    }
                    seen[op.ordinal()]++;
                    for (int i = 0; i < index.length; i++) {
                        int expected = 0;
                        for (PacketEntity entity : map.values())
                            if (predicates.get(i).test(entity)) expected++;
                        assertEquals(
                                expected,
                                (int) viewCount.invoke(map, index[i]),
                                "view " + index[i] + " after " + op + " at seed " + seed + " step " + step);
                        if (expected > 0) nonZero[i]++;
                    }
                }
            }
            for (Op op : Op.values()) assertTrue(seen[op.ordinal()] > 20, op + " ran " + seen[op.ordinal()]);
            for (int i = 0; i < nonZero.length; i++) assertTrue(nonZero[i] > 100, "view " + index[i] + " nonzero");
        }
    }

    private enum Op {
        PUT_NEW,
        PUT_SAME_TYPE,
        PUT_OTHER_TYPE,
        REMOVE_PRESENT,
        REMOVE_ABSENT,
        CLEAR,
        BOXED_PUT,
        BOXED_REMOVE,
        KEY_SET_REMOVE,
        KEY_SET_CLEAR,
        PUT_ALL,
        PUT_IF_ABSENT,
        REMOVE_VALUE,
        REPLACE_VALUE,
        REPLACE,
        COMPUTE_IF_ABSENT,
        COMPUTE_IF_ABSENT_FUNCTION,
        COMPUTE_IF_PRESENT,
        COMPUTE,
        MERGE,
        REPLACE_ALL
    }

    private static PacketEntity pick(Map<Integer, List<PacketEntity>> byType, int type, Random random) {
        List<PacketEntity> instances = byType.get(type);
        return instances.get(random.nextInt(instances.size()));
    }

    // Two instances of every type the tracker builds, each of the subclass it builds for that type.
    private static Map<Integer, List<PacketEntity>> pool(CompensatedEntities entities) {
        Map<Integer, List<PacketEntity>> byType = new LinkedHashMap<>();
        int id = 1000;
        for (EntityTypes.Type type : EntityTypes.defaults().types()) {
            for (int copy = 0; copy < 2; copy++, id++) {
                entities.addEntity(id, type.id(), new Vec3(8, 64, 8), 0, 0, 0);
                PacketEntity entity = entities.entityMap.get(id);
                if (entity != null)
                    byType.computeIfAbsent(type.id(), k -> new ArrayList<>()).add(entity);
            }
        }
        entities.clearEntityMap();
        byType.values().removeIf(instances -> instances.size() < 2);
        return byType;
    }
}
