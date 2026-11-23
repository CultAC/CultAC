package ac.cult.cultac.protocol;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.data.ModelRegistryData;
import org.junit.jupiter.api.Test;

class ModelRegistryDataTest {
    @Test
    void unknownRegistriesAndBlockStateIdsCannotTurnIntoAirOrAnUnrelatedEntry() {
        var data = ModelRegistryData.load(ProtocolVersion.V1_21_3);
        assertThrows(MalformedPacketException.class, () -> data.blockStateName(-1));
        assertThrows(
                MalformedPacketException.class,
                () -> data.blockStateName(data.blockStates().size()));
        assertThrows(ProtocolResolutionException.class, () -> data.registry("cult:custom_dynamic_registry"));
    }
}
