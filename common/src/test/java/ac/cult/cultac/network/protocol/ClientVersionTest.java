package ac.cult.cultac.network.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientVersionTest {

    @Test
    void theNewestSupportedProtocolResolvesToItsRelease() {
        assertEquals(ClientVersion.V_26_3, ClientVersion.fromProtocolVersion(777));
        assertEquals("26.3", ClientVersion.fromProtocolVersion(777).getReleaseName());
    }

    @Test
    void olderProtocolsResolveToTheirRelease() {
        assertEquals(ClientVersion.V_26_2, ClientVersion.fromProtocolVersion(776));
        assertEquals(ClientVersion.V_1_21_11, ClientVersion.fromProtocolVersion(774));
        assertEquals(ClientVersion.V_1_8, ClientVersion.fromProtocolVersion(47));
    }

    @Test
    void aProtocolBetweenTwoReleasesResolvesToTheOlderOne() {
        assertEquals(ClientVersion.V_1_19, ClientVersion.fromProtocolVersion(760));
    }

    @Test
    void aProtocolAboveTheNewestSupportedIsHigherThanSupported() {
        assertEquals(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS, ClientVersion.fromProtocolVersion(778));
    }
}
