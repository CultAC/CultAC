package ac.cult.cultac.utils.data.packetentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The type classifications a PacketEntity holds equal EntityTypeUtil's for every model entity type. */
class PacketEntityTypeFlagsTest {
    @Test
    void flagsMatchEntityTypeUtilForEveryModelType() {
        List<Integer> ids = new ArrayList<>();
        for (EntityTypes.Type type : EntityTypes.defaults().types()) ids.add(type.id());
        assertFalse(ids.isEmpty());
        ids.add(-1);
        boolean sawHappyGhast = false;
        for (int id : ids) {
            PacketEntity entity = new PacketEntity(id, 1);
            String name = EntityTypeUtil.getKey(id).toString();
            assertEquals(EntityTypeUtil.isLiving(id), entity.isLivingEntity(), name + " living");
            assertEquals(EntityTypeUtil.isBoat(id), entity.isBoat(), name + " boat");
            assertEquals(EntityTypeUtil.isMinecart(id), entity.isMinecart(), name + " minecart");
            assertEquals(EntityTypeUtil.isHappyGhast(id), entity.isHappyGhast(), name + " happy ghast");
            sawHappyGhast |= entity.isHappyGhast();
        }
        assertTrue(sawHappyGhast);
    }
}
