package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelRegistryData;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRegistryDataTest {
    private static final List<ProtocolVersion> VERSIONS = List.of(ProtocolVersion.values());

    @Test
    void everySupportedOfficialRegistryPreservesExactNamesAcrossVersions() {
        var models = VERSIONS.stream().map(ModelRegistryData::load).toList();
        int remapped = 0;
        for (var source : models)
            for (var target : models) {
                for (String key : List.of("minecraft:item", "minecraft:block", "minecraft:data_component_type")) {
                    var table = source.registry(key);
                    for (int id = 0; id < table.size(); id++) {
                        int mapped = source.translateRegistryId(key, id, target);
                        if (mapped == -1) continue;
                        assertEquals(table.name(id), target.registry(key).name(mapped));
                        assertEquals(id, target.translateRegistryId(key, mapped, source));
                        if (mapped != id) remapped++;
                    }
                }
            }
        assertTrue(remapped > 0, "These versions cannot share numeric IDs without mapping");
    }

    @Test
    void stateMappingsPreserveEveryPropertyAndRemainReversibleWherePresent() {
        var old = ModelRegistryData.load(ProtocolVersion.V1_21_3);
        var modern = ModelRegistryData.load(ProtocolVersion.V26_3);
        int shared = 0, remapped = 0;
        for (int id = 0; id < old.blockStates().size(); id++) {
            int mapped = old.translateBlockState(id, modern);
            if (mapped == -1) continue;
            assertEquals(old.blockStateName(id), modern.blockStateName(mapped));
            assertEquals(id, modern.translateBlockState(mapped, old));
            shared++;
            if (mapped != id) remapped++;
        }
        assertTrue(shared > 20000);
        assertTrue(remapped > 0);
        assertThrows(
                UnsupportedOperationException.class, () -> old.blockStates().clear());
    }

    @Test
    void missingAndInvalidIdsCannotTurnIntoAirOrAnUnrelatedItem() {
        var old = ModelRegistryData.load(ProtocolVersion.V1_21_3);
        var newer = ModelRegistryData.load(ProtocolVersion.V1_21_11);
        int item = newer.registry("minecraft:item").id("minecraft:firefly_bush");
        assertTrue(item >= 0);
        assertEquals(-1, newer.translateRegistryId("minecraft:item", item, old));
        assertThrows(MalformedPacketException.class, () -> old.translateBlockState(-1, newer));
        assertThrows(
                MalformedPacketException.class,
                () -> old.translateRegistryId("minecraft:item", Integer.MAX_VALUE, newer));
        assertThrows(ProtocolResolutionException.class, () -> old.registry("cult:custom_dynamic_registry"));
    }
}
