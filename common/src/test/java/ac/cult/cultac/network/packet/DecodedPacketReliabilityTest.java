package ac.cult.cultac.network.packet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import org.junit.Test;

public final class DecodedPacketReliabilityTest {
    @Test
    public void publicHelpersUseEachObservedBoundaryAcrossAllHundredPairs() {
        for (ProtocolVersion original : ProtocolVersion.values()) {
            ClientVersion client = ClientVersion.fromProtocolVersion(original.protocol());
            for (ProtocolVersion observed : ProtocolVersion.values()) {
                org.junit.Assert.assertEquals(
                        original.protocol() < 771 == observed.protocol() < 771,
                        DecodedPacketReliability.nativeInputFamilyReliable(client, observed));
                org.junit.Assert.assertEquals(
                        original.protocol() < 775 == observed.protocol() < 775,
                        DecodedPacketReliability.interactionFamilyReliable(client, observed));
            }
        }
    }

    @Test
    public void nativeInputReliabilityRequiresSameSideOfOneTwentyOneSix() {
        assertTrue(DecodedPacketReliability.nativeInputFamilyReliable(ClientVersion.V_1_21_5, ClientVersion.V_1_21_5));
        assertTrue(DecodedPacketReliability.nativeInputFamilyReliable(ClientVersion.V_1_21_6, ClientVersion.V_26_2));
        assertFalse(DecodedPacketReliability.nativeInputFamilyReliable(ClientVersion.V_1_21_5, ClientVersion.V_1_21_6));
        assertFalse(DecodedPacketReliability.nativeInputFamilyReliable(ClientVersion.V_26_2, ClientVersion.V_1_21_5));
    }

    @Test
    public void interactionReliabilityRequiresSameSideOfTwentySixOne() {
        assertTrue(
                DecodedPacketReliability.interactionFamilyReliable(ClientVersion.V_1_21_11, ClientVersion.V_1_21_11));
        assertTrue(DecodedPacketReliability.interactionFamilyReliable(ClientVersion.V_26_1, ClientVersion.V_26_2));
        assertFalse(DecodedPacketReliability.interactionFamilyReliable(ClientVersion.V_1_21_11, ClientVersion.V_26_1));
        assertFalse(DecodedPacketReliability.interactionFamilyReliable(ClientVersion.V_26_2, ClientVersion.V_1_21_11));
    }
}
