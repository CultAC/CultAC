package ac.cult.cultac.network.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientVersionTest {
    @Test
    void recognizedWireVersionsRoundTrip() {
        for (ClientVersion version : ClientVersion.values()) {
            assertSame(version, ClientVersion.fromProtocolVersion(version.getProtocolVersion()));
        }
    }

    @Test
    void snapshotWireIdsDoNotDetermineReleaseOrder() {
        assertTrue(ClientVersion.V_26_3_RC_1.isNewerThan(ClientVersion.V_26_2));
        assertTrue(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS.isNewerThan(ClientVersion.V_26_3_RC_1));
        assertTrue(ClientVersion.V_26_3_RC_1.isOlderThan(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS));
        assertFalse(ClientVersion.V_26_3_RC_1.isOlderThan(ClientVersion.V_26_2));
        for (ClientVersion version : ClientVersion.values()) {
            assertTrue(version.isNewerThanOrEquals(version));
            assertTrue(version.isOlderThanOrEquals(version));
            assertFalse(version.isOlderThan(version));
            assertFalse(version.isNewerThan(version));
        }
    }

    @Test
    void unknownReleasesAndSnapshotsAreNotTreatedAsRc1() {
        assertSame(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS, ClientVersion.fromProtocolVersion(777));
        assertSame(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS, ClientVersion.fromProtocolVersion(1073742159));
        assertSame(ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS, ClientVersion.fromProtocolVersion(1073742161));
        assertSame(ClientVersion.V_1_21_7, ClientVersion.fromProtocolVersion(772));
        assertSame(ClientVersion.V_1_20_5, ClientVersion.fromProtocolVersion(766));
    }
}
