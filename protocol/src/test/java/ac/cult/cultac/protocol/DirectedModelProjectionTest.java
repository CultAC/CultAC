package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
import ac.cult.cultac.protocol.data.ModelRegistryNames;
import org.junit.jupiter.api.Test;

class DirectedModelProjectionTest {
    @Test
    void bothDirectionsAreIndependentAndEveryNativeStateHasAClientVisibleProjection() {
        for (var source : ProtocolVersion.values()) {
            for (var target : ProtocolVersion.values()) {
                if (source == target) continue;
                var nativeData = ModelRegistryData.load(source);
                var old = ModelRegistryData.load(target);
                var backwards = ModelIdMappings.project(source, target);
                var forwards = ModelIdMappings.project(target, source);
                var states = ModelBlockStates.project(source, target);
                assertEquals(nativeData.blockStates().size(), backwards.blockStateCount());
                assertEquals(nativeData.registry("minecraft:block").size(), backwards.blockCount());
                assertEquals(nativeData.registry("minecraft:item").size(), backwards.itemCount());
                for (int id = 0; id < nativeData.blockStates().size(); id++) {
                    assertEquals(backwards.blockState(id), states.toModel(id));
                    assertTrue(states.toModel(id) >= 0
                            && states.toModel(id) < old.blockStates().size());
                }
                for (int id = 0; id < old.blockStates().size(); id++)
                    assertEquals(forwards.blockState(id), states.toHost(id));
                var names = ModelRegistryNames.project(source, target);
                for (int id = 0; id < backwards.itemCount(); id++)
                    assertEquals(
                            old.registry("minecraft:item").name(backwards.item(id)),
                            names.modelItem(
                                    nativeData.registry("minecraft:item").name(id)));
                for (int id = 0; id < old.registry("minecraft:item").size(); id++)
                    assertEquals(
                            nativeData.registry("minecraft:item").name(forwards.item(id)),
                            names.hostItem(old.registry("minecraft:item").name(id)));
                for (int id = 0; id < backwards.blockCount(); id++)
                    assertEquals(
                            backwards.block(id) == -1
                                    ? null
                                    : old.registry("minecraft:block").name(backwards.block(id)),
                            names.modelBlock(
                                    nativeData.registry("minecraft:block").name(id)));
            }
        }
    }

    @Test
    void removedTagMembersAreNotInventedAsFallbackBlockMemberships() {
        var names = ModelRegistryNames.project(ProtocolVersion.V26_3, ProtocolVersion.V1_21_11);
        assertNull(names.modelBlock("minecraft:golden_dandelion"));
        assertEquals("minecraft:grass_block", names.modelBlock("minecraft:grass_block"));
        assertEquals("minecraft:white_bed", names.modelItem("minecraft:white_bed"));
    }
}
