package ac.cult.cultac.manager.datastore;

import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.protocol.util.viaversion.ViaVersionUtil;
import com.viaversion.viaversion.api.Via;
import net.minecraft.SharedConstants;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Maps legacy client-version release-name strings (e.g. {@code "1.21.1"}) to
 * PacketEvents protocol-version numbers. Lives in the plugin module because
 * PacketEvents is only on the classpath here — the migrator in the shared
 * storage module accepts a generic {@code Function<String, Integer>} so it
 * stays PacketEvents-free and portable.
 * <p>
 * Returns {@code -1} when the string doesn't resolve to a known
 * {@link ClientVersion} — unknown / pre-1.8 / freshly-released / bogus.
 */
public final class ClientVersionResolver {

    private ClientVersionResolver() {}

    public static int legacyStringToPvn(@Nullable String versionString) {
        if (versionString == null) return -1;
        String trimmed = versionString.trim();
        if (trimmed.isEmpty()) return -1;
        // Multi-version aliases are slash-separated.
        String wanted = trimmed.toUpperCase(Locale.ROOT);
        for (ClientVersion version : ClientVersion.values()) {
            if (version == ClientVersion.HIGHER_THAN_SUPPORTED_VERSIONS) continue;
            for (String alias : version.getReleaseName().split("/")) {
                if (alias.toUpperCase(Locale.ROOT).equals(wanted)) {
                    return version.getProtocolVersion();
                }
            }
        }
        return -1;
    }

    /**
     * Resolves the protocol version of the connection behind {@code user}.
     * Mirrors CultPlayer.getClientVersion(): ViaVersion when installed, else
     * the server's native protocol version.
     */
    public static int resolveProtocolVersion(User user) {
        UUID uuid = user.getUUID();
        if (uuid != null && ViaVersionUtil.isAvailable()) {
            try {
                int protocolVersion = Via.getAPI().getPlayerVersion(uuid);
                if (protocolVersion > 0) {
                    return protocolVersion;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the server's native protocol version
            }
        }
        return SharedConstants.getProtocolVersion();
    }
}
