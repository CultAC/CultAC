package ac.grim.grimac.network.packet;

import ac.grim.grimac.network.protocol.ClientVersion;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DecodedPacketReliabilityTest {
    @Test
    public void nativeInputReliabilityRequiresSameSideOfOneTwentyOneSix() {
        assertTrue(DecodedPacketReliability.nativeInputFamilyReliable(
                ClientVersion.V_1_21_5, ClientVersion.V_1_21_5));
        assertTrue(DecodedPacketReliability.nativeInputFamilyReliable(
                ClientVersion.V_1_21_6, ClientVersion.V_26_2));
        assertFalse(DecodedPacketReliability.nativeInputFamilyReliable(
                ClientVersion.V_1_21_5, ClientVersion.V_1_21_6));
        assertFalse(DecodedPacketReliability.nativeInputFamilyReliable(
                ClientVersion.V_26_2, ClientVersion.V_1_21_5));
    }

    @Test
    public void interactionReliabilityRequiresSameSideOfTwentySixOne() {
        assertTrue(DecodedPacketReliability.interactionFamilyReliable(
                ClientVersion.V_1_21_11, ClientVersion.V_1_21_11));
        assertTrue(DecodedPacketReliability.interactionFamilyReliable(
                ClientVersion.V_26_1, ClientVersion.V_26_2));
        assertFalse(DecodedPacketReliability.interactionFamilyReliable(
                ClientVersion.V_1_21_11, ClientVersion.V_26_1));
        assertFalse(DecodedPacketReliability.interactionFamilyReliable(
                ClientVersion.V_26_2, ClientVersion.V_1_21_11));
    }
}
