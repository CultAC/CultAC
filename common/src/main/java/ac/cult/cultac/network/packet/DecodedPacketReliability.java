package ac.cult.cultac.network.packet;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;

/**
 * Identifies decoded packet families whose original identity is preserved
 * across the active client/server protocol pair. Via may merge, split, or
 * synthesize these families when the pair crosses the listed boundary.
 */
public final class DecodedPacketReliability {
    private DecodedPacketReliability() {}

    public static boolean nativeInputFamilyReliable(ClientVersion clientVersion, ProtocolVersion observedProtocol) {
        return nativeInputFamilyReliable(clientVersion, ClientVersion.fromProtocolVersion(observedProtocol.protocol()));
    }

    public static boolean interactionFamilyReliable(ClientVersion clientVersion, ProtocolVersion observedProtocol) {
        return interactionFamilyReliable(clientVersion, ClientVersion.fromProtocolVersion(observedProtocol.protocol()));
    }

    static boolean nativeInputFamilyReliable(ClientVersion clientVersion, ClientVersion observedVersion) {
        return onSameSide(clientVersion, observedVersion, ClientVersion.V_1_21_6);
    }

    static boolean interactionFamilyReliable(ClientVersion clientVersion, ClientVersion observedVersion) {
        return onSameSide(clientVersion, observedVersion, ClientVersion.V_26_1);
    }

    private static boolean onSameSide(
            ClientVersion clientVersion, ClientVersion observedVersion, ClientVersion boundary) {
        return clientVersion.isOlderThan(boundary) == observedVersion.isOlderThan(boundary);
    }
}
