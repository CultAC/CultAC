package ac.cult.cultac.network.packet;

import ac.cult.cultac.network.protocol.ClientVersion;
import net.minecraft.SharedConstants;

/**
 * Identifies decoded packet families whose original identity is preserved
 * across the active client/server protocol pair. Via may merge, split, or
 * synthesize these families when the pair crosses the listed boundary.
 */
public final class DecodedPacketReliability {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private DecodedPacketReliability() {
    }

    public static boolean nativeInputFamilyReliable(ClientVersion clientVersion) {
        return nativeInputFamilyReliable(clientVersion, SERVER_VERSION);
    }

    public static boolean interactionFamilyReliable(ClientVersion clientVersion) {
        return interactionFamilyReliable(clientVersion, SERVER_VERSION);
    }

    static boolean nativeInputFamilyReliable(ClientVersion clientVersion, ClientVersion serverVersion) {
        return onSameSide(clientVersion, serverVersion, ClientVersion.V_1_21_6);
    }

    static boolean interactionFamilyReliable(ClientVersion clientVersion, ClientVersion serverVersion) {
        return onSameSide(clientVersion, serverVersion, ClientVersion.V_26_1);
    }

    private static boolean onSameSide(
            ClientVersion clientVersion,
            ClientVersion serverVersion,
            ClientVersion boundary
    ) {
        return clientVersion.isOlderThan(boundary) == serverVersion.isOlderThan(boundary);
    }
}
