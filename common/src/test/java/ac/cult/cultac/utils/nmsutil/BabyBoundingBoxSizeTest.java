package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.blocksim.entity.EntityTypes;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import org.junit.jupiter.api.Test;

class BabyBoundingBoxSizeTest {
    @Test
    void babyFoxUsesItsScaleFrom26Point1() {
        // 1.21.11 Fox#BABY_DIMENSIONS is scale(0.5F); 26.1, 26.2 and 26.3 use scale(0.6F).
        assertBaby(EntityTypeIds.FOX, ClientVersion.V_1_21_11, 0.6F * 0.5F, 0.7F * 0.5F);
        for (ClientVersion version :
                new ClientVersion[] {ClientVersion.V_26_1, ClientVersion.V_26_2, ClientVersion.V_26_3}) {
            assertBaby(EntityTypeIds.FOX, version, 0.36F, 0.42F);
        }
    }

    @Test
    void chestedHorseBabiesKeepHalfScale() {
        int traderLlama = EntityTypes.defaults().byKey("trader_llama").id();
        for (ClientVersion version :
                new ClientVersion[] {ClientVersion.V_1_21_11, ClientVersion.V_26_1, ClientVersion.V_26_3}) {
            // AbstractChestedHorse and Llama#BABY_DIMENSIONS are scale(0.5F) in every supported version.
            for (int type : new int[] {EntityTypeIds.DONKEY, EntityTypeIds.MULE, EntityTypeIds.LLAMA, traderLlama}) {
                assertScaled(type, version, 0.5F);
            }
        }
    }

    @Test
    void horseBabiesUseSevenTenthsFrom26Point1() {
        for (int type : new int[] {EntityTypeIds.HORSE, EntityTypeIds.SKELETON_HORSE, EntityTypeIds.ZOMBIE_HORSE}) {
            assertScaled(type, ClientVersion.V_1_21_11, 0.5F);
            assertScaled(type, ClientVersion.V_26_1, 0.7F);
            assertScaled(type, ClientVersion.V_26_3, 0.7F);
        }
    }

    private static void assertScaled(int type, ClientVersion version, float scale) {
        EntityTypes.Type base = EntityTypes.defaults().byId(type);
        assertBaby(type, version, base.width() * scale, base.height() * scale);
    }

    private static void assertBaby(int type, ClientVersion version, float width, float height) {
        PacketEntity entity = new PacketEntity(type, 1);
        entity.isBaby = true;
        String name = EntityTypeUtil.getKey(type).getPath() + " on " + version;
        assertEquals(width, BoundingBoxSize.getWidth(entity, version), name);
        assertEquals(height, BoundingBoxSize.getHeight(entity, version), name);
    }
}
