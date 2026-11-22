package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.data.ModelIdMappings;
import ac.cult.cultac.protocol.data.ModelRegistryData;
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
            }
        }
    }
}
