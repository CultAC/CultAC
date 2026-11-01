package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The riding-root tests that take roots read once per scan decide what the per-entity tests decide. */
class RidingRootHoistTest {
    private static final String[] TYPES = {"horse", "oak_boat", "player", "zombie", "chicken", "armor_stand"};
    private static final int CONFIGS = 4000;

    private record World(CompensatedEntities entities, List<PacketEntity> all, List<Integer> ids) {}

    private static World world(JavaTickFixture f) {
        CompensatedEntities entities = f.player.compensatedEntities;
        List<PacketEntity> all = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int id = 3000 + i;
            entities.addEntity(id, typeId(TYPES[i % TYPES.length]), new Vec3(i, JavaTickFixture.FLOOR_Y, 0), 0, 0, 0);
            all.add(entities.getEntity(id));
            ids.add(id);
        }
        for (int i = 0; i < 2; i++) {
            int id = 3100 + i;
            all.add(new PacketEntity(typeId(TYPES[i]), id));
            ids.add(id);
        }
        all.add(entities.getSelf());
        return new World(entities, all, ids);
    }

    private static int typeId(String key) {
        return EntityTypes.defaults().byKey(key).id();
    }

    private static void ride(World world, Random random) {
        for (PacketEntity entity : world.all()) entity.eject();
        for (PacketEntity entity : world.all()) {
            if (random.nextInt(3) != 0) continue;
            PacketEntity vehicle = world.all().get(random.nextInt(world.all().size()));
            if (vehicle != entity) entity.mount(vehicle);
        }
        world.entities().vehicles.serverPlayerVehicle = switch (random.nextInt(4)) {
            case 0 -> null;
            case 1 -> 9999;
            default -> world.ids().get(random.nextInt(world.ids().size()));
        };
    }

    // CompensatedEntities#rootOf: the end of the riding chain, null after more hops than tracked entities.
    private static PacketEntity chainRoot(CompensatedEntities entities, PacketEntity entity) {
        PacketEntity root = entity;
        for (int hops = 0; root.getRiding() != null; hops++) {
            if (hops > entities.entityMap.size()) return null;
            root = root.getRiding();
        }
        return root;
    }

    // CompensatedEntities#isUntrackedRider: a rider whose chain root is neither own riding root, or has no root.
    private static boolean untrackedRider(CompensatedEntities entities, PacketEntity entity) {
        if (entity.getRiding() == null) return false;
        PacketEntity root = chainRoot(entities, entity);
        return root == null || root != entities.selfRidingRoot() && root != entities.serverVehicleRoot();
    }

    @Test
    void sharedRootWithTheSelfRootReadOnceMatchesThePerEntityWalk() throws Exception {
        try (var f = new JavaTickFixture()) {
            World world = world(f);
            CompensatedEntities entities = world.entities();
            Random random = new Random(11);
            int shared = 0;
            int cycles = 0;
            int checked = 0;
            for (int config = 0; config < CONFIGS; config++) {
                ride(world, random);
                PacketEntity rootOfSelf = entities.rootOfSelf();
                if (rootOfSelf == null) cycles++;
                for (PacketEntity entity : world.all()) {
                    PacketEntity root = chainRoot(entities, entity);
                    boolean expected = root != null && root == chainRoot(entities, entities.getSelf());
                    assertEquals(
                            expected,
                            entities.sharesRootVehicleWithSelf(entity, rootOfSelf),
                            "config " + config + " entity " + entity.getEntityId());
                    if (expected && entity != entities.getSelf()) shared++;
                    checked++;
                }
            }
            assertTrue(shared > 0 && cycles > 0, "shared=" + shared + " cycles=" + cycles + " checked=" + checked);
        }
    }

    @Test
    void untrackedRiderWithRootsReadOnceMatchesThePerEntityTest() throws Exception {
        try (var f = new JavaTickFixture()) {
            World world = world(f);
            CompensatedEntities entities = world.entities();
            Random random = new Random(13);
            int untracked = 0;
            int trackedRiders = 0;
            int serverRoots = 0;
            int riders = 0;
            int rootlessRiders = 0;
            for (int config = 0; config < CONFIGS; config++) {
                ride(world, random);
                PacketEntity selfRidingRoot = entities.selfRidingRoot();
                PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
                if (serverVehicleRoot != null && serverVehicleRoot != selfRidingRoot) serverRoots++;
                for (PacketEntity entity : world.all()) {
                    boolean expected = untrackedRider(entities, entity);
                    String step = "config " + config + " entity " + entity.getEntityId();
                    assertEquals(expected, entities.isUntrackedRider(entity), step);
                    assertEquals(expected, entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot), step);
                    if (expected) untracked++;
                    else if (entity.getRiding() != null) trackedRiders++;
                    if (selfRidingRoot == null && serverVehicleRoot == null && entity.getRiding() != null) {
                        if (chainRoot(entities, entity) == null) rootlessRiders++;
                        else riders++;
                    }
                }
            }
            assertTrue(
                    untracked > 0 && trackedRiders > 0 && serverRoots > 0 && riders > 0 && rootlessRiders > 0,
                    "untracked=" + untracked + " trackedRiders=" + trackedRiders + " serverRoots=" + serverRoots
                            + " riders=" + riders + " rootlessRiders=" + rootlessRiders);
        }
    }
}
