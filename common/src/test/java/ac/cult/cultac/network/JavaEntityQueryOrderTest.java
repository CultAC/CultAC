package ac.cult.cultac.network;

import static ac.cult.cultac.network.JavaTickFixture.FLOOR_Y;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import org.junit.jupiter.api.Test;

class JavaEntityQueryOrderTest {
    @Test
    void sectionReentryChangesQueryOrderWithoutChangingTickOrder() throws Exception {
        try (var f = new JavaTickFixture()) {
            var entities = f.player.compensatedEntities;
            Vec3 here = new Vec3(.5, FLOOR_Y, .5);
            Vec3 away = here.add(32, 0, 0);
            entities.observeLocalPlayerPosition(here);
            entities.addEntity(43, EntityTypeIds.OAK_BOAT, here, 0, 0, 0);
            var boat = entities.getEntity(43);
            assertTrue(entities.localPlayerPrecedesInEntityQuery(boat, here, here, null, 0));
            entities.observeLocalPlayerPosition(away);
            entities.observeLocalPlayerPosition(here);
            assertFalse(entities.localPlayerPrecedesInEntityQuery(boat, here, here, null, 0));
            boat.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(f.player, away.x, away.y, away.z, boat));
            boat.setPositionRaw(GetBoundingBox.getPacketEntityBoundingBox(f.player, here.x, here.y, here.z, boat));
            assertTrue(entities.localPlayerPrecedesInEntityQuery(boat, here, here, null, 0));

            // EntitySectionStorage visits X first, then the packed Z/Y fields within that X.
            assertTrue(entities.localPlayerPrecedesInEntityQuery(boat, here, away, null, 0));
            assertFalse(entities.localPlayerPrecedesInEntityQuery(boat, away, here, null, 0));
            assertTrue(entities.localPlayerPrecedesInEntityQuery(boat, here, here.add(0, 0, -32), null, 0));
            assertFalse(entities.localPlayerPrecedesInEntityQuery(boat, here.add(0, 0, -32), here, null, 0));
        }
    }
}
