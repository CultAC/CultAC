package ac.cult.cultac.cloud;

import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The upload's settings, read from the config alone. An empty {@code url} or {@code apiKey}, or no {@code protocols},
 * leaves the upload off. {@code serverIdTemplate} names each socket's server id through {@link CloudServerIds};
 * {@code protocols} are the intake protocols this platform offers, highest last.
 */
public record CloudSettings(
        String url, String apiKey, String serverIdTemplate, String proxyId, List<Integer> protocols) {
    public static final String DEFAULT_SERVER_ID = "{proxy}.{server}";
    /** A server's default: its own name, since no proxy shares its key. */
    public static final String OWN_SERVER_ID = "{server}";
    /** A server's offer: protocol 3 stays the fallback when zstd cannot load. */
    public static final List<Integer> SERVER_PROTOCOLS = List.of(3, 5);
    /** A proxy's offer: protocol 5 alone, since protocol 3's raw chunk packets cost a proxy too much. */
    public static final List<Integer> PROXY_PROTOCOLS = List.of(5);

    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");

    public static final CloudSettings OFF = new CloudSettings("", "", DEFAULT_SERVER_ID, "", List.of());

    public CloudSettings {
        url = url == null ? "" : url.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        serverIdTemplate =
                serverIdTemplate == null || serverIdTemplate.isBlank() ? DEFAULT_SERVER_ID : serverIdTemplate.strip();
        proxyId = proxyId == null ? "" : proxyId.strip();
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
    }

    /**
     * Reads {@code cloud.url}, {@code cloud.api-key}, {@code cloud.server-id} and {@code cloud.proxy-id}, an empty
     * {@code cloud.server-id} picking {@code defaultServerId}.
     */
    public static CloudSettings read(ConfigManager config, List<Integer> protocols, String defaultServerId) {
        String serverId = config.getStringElse("cloud.server-id", "");
        String url = config.getStringElse("cloud.url", "");
        if (url != null && !url.isBlank() && !allowedUrl(url.strip())) {
            LogUtil.warn("Cloud upload off: cloud.url must be https, or http to a loopback host, with a host and no"
                    + " user info, query or fragment");
            url = "";
        }
        return new CloudSettings(
                url,
                config.getStringElse("cloud.api-key", ""),
                serverId.isBlank() ? defaultServerId : serverId,
                config.getStringElse("cloud.proxy-id", ""),
                protocols);
    }

    /**
     * Whether {@code url} may carry the API key: {@code https} with a host, or {@code http} to a loopback host, without
     * user info, query or fragment.
     */
    static boolean allowedUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException malformed) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) return false;
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "https" -> true;
            case "http" -> loopback(uri.getHost());
            default -> false;
        };
    }

    /** Whether {@code host}, as {@link URI#getHost} gives it, names this machine without a DNS lookup. */
    public static boolean loopback(String host) {
        if (host == null) return false;
        return host.equalsIgnoreCase("localhost")
                || host.equals("[::1]")
                || LOOPBACK_V4.matcher(host).matches();
    }

    /** Whether the upload runs: a service, a key and a protocol to offer. */
    public boolean enabled() {
        return !url.isEmpty() && !apiKey.isEmpty() && !protocols.isEmpty();
    }
}
