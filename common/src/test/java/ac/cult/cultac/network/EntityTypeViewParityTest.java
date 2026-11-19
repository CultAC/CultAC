package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Each type view holds exactly the entityMap values its predicate accepts, in entityMap iteration order, and removing
 * an absent id keeps the views.
 */
class EntityTypeViewParityTest {
    private static List<PacketEntity> filtered(CompensatedEntities entities, Predicate<PacketEntity> accept) {
        List<PacketEntity> out = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) if (accept.test(entity)) out.add(entity);
        return out;
    }

    private static void assertViews(CompensatedEntities entities, String step) {
        assertEquals(filtered(entities, PacketEntity::mayPushOrCollide), entities.pushOrCollideCandidates(), step);
        assertEquals(filtered(entities, PacketEntity::isBoat), entities.boats(), step);
        assertEquals(
                filtered(
                        entities,
                        e -> e.isBoat() || e.isMinecart() || e.type == EntityTypeIds.SHULKER || e.isHappyGhast()),
                entities.poseCollisionCandidates(),
                step);
        assertEquals(
                filtered(
                        entities,
                        e -> e.isBoat() || e.type == EntityTypeIds.SHULKER || e instanceof PacketEntityHappyGhast),
                entities.unknownCollisionCandidates(),
                step);
    }

    @Test
    void viewsFollowEveryMutationInMapOrder() throws Exception {
        List<Integer> types = new ArrayList<>();
        for (EntityTypes.Type type : EntityTypes.defaults().types()) types.add(type.id());
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            Random random = new Random(7);
            List<Integer> live = new ArrayList<>();
            int nonEmptyBoats = 0;
            int absentRemoves = 0;
            for (int step = 0; step < 4000; step++) {
                int op = random.nextInt(100);
                if (op < 55 || live.isEmpty()) {
                    int id = 100 + random.nextInt(400);
                    int type = types.get(random.nextInt(types.size()));
                    entities.addEntity(id, type, new Vec3(random.nextInt(64), 64, random.nextInt(64)), 0, 0, 0);
                    if (entities.getEntity(id) != null && !live.contains(id)) live.add(id);
                } else if (op < 90) {
                    entities.removeEntity(live.remove(random.nextInt(live.size())));
                } else if (op < 95) {
                    List<PacketEntity> before = entities.pushOrCollideCandidates();
                    entities.removeEntity(500 + random.nextInt(100));
                    assertSame(before, entities.pushOrCollideCandidates(), "step " + step);
                    absentRemoves++;
                } else if (op < 99) {
                    int id = live.get(random.nextInt(live.size()));
                    entities.entityMap.put(id, new PacketEntity(types.get(random.nextInt(types.size())), id));
                } else {
                    entities.clearEntityMap();
                    live.clear();
                }
                assertViews(entities, "step " + step);
                if (!entities.boats().isEmpty()) nonEmptyBoats++;
            }
            assertTrue(
                    nonEmptyBoats > 0 && absentRemoves > 0,
                    "nonEmptyBoats=" + nonEmptyBoats + " absentRemoves=" + absentRemoves);
        }
    }
}
