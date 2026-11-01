package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.data.packetentity.PacketEntityCamel;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHook;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHorse;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A metadata value of another type than the model reads at its index is ignored, not cast. */
class MetadataValueTypeTest {
    @Test
    void mistypedRideableHorseCamelAndHookValuesAreIgnored() throws Exception {
        try (var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var entities = fixture.player.compensatedEntities;
            var layout = WatchableIndexUtil.forModel(fixture.player.user.getCultConnection());
            entities.addEntity(1, EntityTypeIds.HORSE, new Vec3(0, 64, 0), 0, 0, 0);
            entities.addEntity(2, EntityTypeIds.CAMEL, new Vec3(4, 64, 0), 0, 0, 0);
            entities.addEntity(3, EntityTypeIds.PIG, new Vec3(8, 64, 0), 0, 0, 0);
            entities.addEntity(4, EntityTypeIds.STRIDER, new Vec3(12, 64, 0), 0, 0, 0);
            entities.addEntity(5, EntityTypeIds.FISHING_BOBBER, new Vec3(16, 64, 0), 0, 0, 0);

            assertDoesNotThrow(() -> entities.updateEntityMetadata(1, List.of(entry(layout.horseFlags(), 0x02))));
            assertFalse(((PacketEntityHorse) entities.getEntity(1)).isTame);
            assertDoesNotThrow(() -> entities.updateEntityMetadata(2, List.of(entry(layout.camelDash(), (byte) 1))));
            assertFalse(((PacketEntityCamel) entities.getEntity(2)).dashing);
            assertDoesNotThrow(() -> entities.updateEntityMetadata(3, List.of(entry(layout.pigBoostTime(), true))));
            assertDoesNotThrow(
                    () -> entities.updateEntityMetadata(4, List.of(entry(layout.striderBoostTime(), (byte) 3))));
            int attached = ((PacketEntityHook) entities.getEntity(5)).attached;
            assertDoesNotThrow(() -> entities.updateEntityMetadata(
                    5, List.of(entry(WatchableIndexUtil.FISHING_HOOKED_ENTITY, (byte) 9))));
            assertEquals(attached, ((PacketEntityHook) entities.getEntity(5)).attached);
        }
    }

    private static EntityMetadata.Entry entry(int index, Object value) {
        return new EntityMetadata.Entry(index, value, ByteBuffer.allocate(0));
    }
}
