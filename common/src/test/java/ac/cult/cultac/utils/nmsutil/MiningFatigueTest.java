package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.network.protocol.ClientVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningFatigueTest {
    @Test
    void fatigueThreeAndFourUseTheNewScales() {
        assertEquals(0.216F, MiningFatigue.apply(8.0F, 2, ClientVersion.V_26_3_RC_1));
        assertEquals(0.0648F, MiningFatigue.apply(8.0F, 3, ClientVersion.V_26_3_RC_1));
        assertEquals(0.01944F, MiningFatigue.apply(8.0F, 4, ClientVersion.V_26_3_RC_1));
    }

    @Test
    void highAmplifiersDoNotRetainTheLegacyMinimum() {
        assertEquals(0.0F, MiningFatigue.apply(8.0F, 255, ClientVersion.V_26_3_RC_1));
        assertEquals(0.00648F, MiningFatigue.apply(8.0F, 255, ClientVersion.V_26_2));
    }

    @Test
    void olderClientsKeepTheirExistingResults() {
        for (ClientVersion version : ClientVersion.values()) {
            if (version.isOlderThan(ClientVersion.V_26_3_RC_1)) {
                assertEquals(2.4F, MiningFatigue.apply(8.0F, 0, version));
                assertEquals(0.72F, MiningFatigue.apply(8.0F, 1, version));
                assertEquals(0.0216F, MiningFatigue.apply(8.0F, 2, version));
                assertEquals(0.00648F, MiningFatigue.apply(8.0F, 3, version));
            }
        }
    }
}
