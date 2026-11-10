package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Every type view holds exactly the entityMap values its predicate accepts, in entityMap iteration order, whichever
 * views were read between mutations, over spawns, re-adds of a live id under another type, removals of present and
 * absent ids, direct puts and level clears.
 */
class EntityViewCountParityTest {
    private record View(String name, Supplier<List<? extends PacketEntity>> view, Predicate<PacketEntity> accept) {}

    private static List<View> views(CompensatedEntities entities) {
        Predicate<PacketEntity> unknown =
                e -> e.isBoat() || e.type == EntityTypeIds.SHULKER || e instanceof PacketEntityHappyGhast;
        return List.of(
                new View("pushOrCollide", entities::pushOrCollideCandidates, PacketEntity::mayPushOrCollide),
                new View("boats", entities::boats, PacketEntity::isBoat),
                new View("camels", entities::camels, e -> e instanceof PacketEntityCamel),
                new View(
                        "pose",
                        entities::poseCollisionCandidates,
                        e -> e.isBoat() || e.type == EntityTypeIds.SHULKER || e.isHappyGhast()),
                new View("unknown", entities::unknownCollisionCandidates, unknown));
    }

    private static List<PacketEntity> filtered(CompensatedEntities entities, Predicate<PacketEntity> accept) {
        List<PacketEntity> out = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) if (accept.test(entity)) out.add(entity);
        return out;
    }

    @Test
    void viewsFollowEveryMutationWhicheverViewsWereRead() throws Exception {
        List<Integer> types = new ArrayList<>();
        for (EntityTypes.Type type : EntityTypes.defaults().types()) types.add(type.id());
        int[] frequent = {
            EntityTypeIds.OAK_BOAT,
            EntityTypeIds.SHULKER,
            EntityTypeIds.HAPPY_GHAST,
            EntityTypeIds.PLAYER,
            EntityTypes.defaults().byKey("minecraft:camel").id(),
            EntityTypeIds.ITEM
        };
        int[] nonEmpty = new int[5];
        int emptyAfterNonEmpty = 0;
        for (int seed = 0; seed < 4; seed++) {
            try (var f = new JavaTickFixture()) {
                CompensatedEntities entities = f.player.compensatedEntities;
                List<View> views = views(entities);
                boolean[] wasNonEmpty = new boolean[views.size()];
                Random random = new Random(seed);
                for (int step = 0; step < 3000; step++) {
                    int op = random.nextInt(100);
                    int id = 100 + random.nextInt(seed % 2 == 0 ? 40 : 400);
                    int type = random.nextInt(3) == 0
                            ? frequent[random.nextInt(frequent.length)]
                            : types.get(random.nextInt(types.size()));
                    var at = new Vec3(random.nextInt(64), 64, random.nextInt(64));
                    if (op < 50) {
                        entities.addEntity(id, type, at, 0, 0, 0);
                    } else if (op < 85) {
                        entities.removeEntity(id);
                    } else if (op < 97) {
                        entities.entityMap.put(id, new PacketEntity(type, id));
                    } else if (op < 99) {
                        entities.clearEntityMap();
                    } else {
                        entities.clearClientLevel();
                    }
                    // Read a random subset, so a view is sometimes stale across several mutations.
                    for (int v = 0; v < views.size(); v++) {
                        if (random.nextInt(3) != 0) continue;
                        View view = views.get(v);
                        List<PacketEntity> expected = filtered(entities, view.accept());
                        assertEquals(expected, view.view().get(), view.name() + " step " + step);
                        if (!expected.isEmpty()) {
                            nonEmpty[v]++;
                            wasNonEmpty[v] = true;
                        } else if (wasNonEmpty[v]) {
                            emptyAfterNonEmpty++;
                            wasNonEmpty[v] = false;
                        }
                    }
                }
                for (View view : views)
                    assertEquals(filtered(entities, view.accept()), view.view().get(), view.name() + " end");
            }
        }
        for (int v = 0; v < nonEmpty.length; v++) assertTrue(nonEmpty[v] > 50, "view " + v + " " + nonEmpty[v]);
        assertTrue(emptyAfterNonEmpty > 50, "emptyAfterNonEmpty " + emptyAfterNonEmpty);
    }
}
