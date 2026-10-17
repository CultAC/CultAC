package ac.cult.cultac.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.shaded.vialib.api.minecraft.entitydata.EntityDataType;
import ac.cult.shaded.vialib.api.type.Type;
import ac.cult.shaded.vialib.api.type.Types;
import ac.cult.shaded.vialib.util.Key;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The per-version tables {@link PlayerTextValues} reads by: Via's against the vanilla registry reports. */
class PlayerTextValuesTest {
    private static PrivateCodecService decoder;

    @BeforeAll
    static void open() {
        decoder = new PrivateCodecService();
    }

    @AfterAll
    static void close() {
        if (decoder != null) decoder.close();
    }

    @Test
    void everyComponentIdNamesTheVanillaComponent() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            IdTable vanilla = ModelRegistryData.load(version).registry("minecraft:data_component_type");
            var types = PrivateCodecService.types(version);
            for (int id = 0; id < vanilla.size(); id++) {
                assertEquals(
                        vanilla.name(id),
                        Key.namespaced(types.structuredData().key(id).identifier()),
                        version + " component " + id);
            }
            assertNull(types.structuredData().key(vanilla.size()), version + " has more components than vanilla");
        }
    }

    @Test
    void theFirstEntityDataSerializersAreTheSameOnEveryVersion() {
        List<Type<?>> first = List.of(
                Types.BYTE,
                Types.VAR_INT,
                Types.VAR_LONG,
                Types.FLOAT,
                Types.STRING,
                Types.TRUSTED_TAG,
                Types.TRUSTED_OPTIONAL_TAG);
        for (ProtocolVersion version : ProtocolVersion.values()) {
            var types = PrivateCodecService.types(version);
            EntityDataType[] serializers = types.entityDataTypes().values();
            for (int id = 0; id < first.size(); id++)
                assertSame(first.get(id), serializers[id].type(), version + " " + id);
            assertSame(types.item(), serializers[7].type(), version + " item");
            assertSame(Types.BOOLEAN, serializers[8].type(), version + " boolean");
        }
    }

    @Test
    void everyEntityDataSerializerIsReadOrKnownToCarryNoText() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            PlayerTextValues values = new PlayerTextValues(version);
            int serializers =
                    PrivateCodecService.types(version).entityDataTypes().values().length;
            for (int id = 0; id < serializers; id++) {
                assertNotEquals(PlayerTextValues.EntityValue.UNKNOWN, values.entityValue(id), version + " " + id);
            }
            assertEquals(PlayerTextValues.EntityValue.UNKNOWN, values.entityValue(serializers), version + " past");
        }
    }

    @Test
    void theEmptyProfileReadsAsAProfileToItsLastByte() {
        ByteBuf empty = Unpooled.wrappedBuffer(new byte[8]);
        assertNotNull(Types.RESOLVABLE_PROFILE.read(empty));
        assertFalse(empty.isReadable());
    }
}
