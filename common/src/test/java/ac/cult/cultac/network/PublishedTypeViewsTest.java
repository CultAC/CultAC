package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A type view list, once returned, keeps its elements through every later entityMap change and view read, so an
 * iterator over it never fails and an indexed walk visits what the iterator visits.
 */
class PublishedTypeViewsTest {
    private record Held(List<? extends PacketEntity> view, List<PacketEntity> copy, Iterator<? extends PacketEntity> walk) {
        static Held of(List<? extends PacketEntity> view) {
            return new Held(view, new ArrayList<>(view), view.iterator());
        }
    }

    private static List<Held> hold(CompensatedEntities entities) {
        List<Held> held = new ArrayList<>();
        held.add(Held.of(entities.boats()));
        held.add(Held.of(entities.unknownCollisionCandidates()));
        held.add(Held.of(entities.pushOrCollideCandidates()));
        held.add(Held.of(entities.poseCollisionCandidates()));
        held.add(Held.of(entities.camels()));
        return held;
    }

    @Test
    void returnedViewsNeverChange() throws Exception {
        List<Integer> types = new ArrayList<>();
        for (EntityTypes.Type type : EntityTypes.defaults().types()) types.add(type.id());
        try (var f = new JavaTickFixture()) {
            CompensatedEntities entities = f.player.compensatedEntities;
            Random random = new Random(11);
            List<Integer> live = new ArrayList<>();
            int nonEmptyHeld = 0;
            for (int step = 0; step < 3000; step++) {
                List<Held> held = hold(entities);
                int op = random.nextInt(100);
                if (op < 60 || live.isEmpty()) {
                    int id = 100 + random.nextInt(300);
                    int type = types.get(random.nextInt(types.size()));
                    entities.addEntity(id, type, new Vec3(random.nextInt(32), 64, random.nextInt(32)), 0, 0, 0);
                    if (entities.getEntity(id) != null && !live.contains(id)) live.add(id);
                } else if (op < 95) {
                    entities.removeEntity(live.remove(random.nextInt(live.size())));
                } else {
                    entities.clearEntityMap();
                    live.clear();
                }
                hold(entities);
                for (Held h : held) {
                    assertEquals(h.copy(), h.view(), "step " + step);
                    List<PacketEntity> indexed = new ArrayList<>();
                    for (int i = 0, size = h.view().size(); i < size; i++) indexed.add(h.view().get(i));
                    List<PacketEntity> iterated = new ArrayList<>();
                    while (h.walk().hasNext()) iterated.add(h.walk().next());
                    assertEquals(iterated, indexed, "step " + step);
                    if (!h.copy().isEmpty()) nonEmptyHeld++;
                }
            }
            assertTrue(nonEmptyHeld > 1000, "nonEmptyHeld=" + nonEmptyHeld);
        }
    }
}
