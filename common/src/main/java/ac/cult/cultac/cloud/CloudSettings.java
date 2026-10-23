package ac.cult.cultac.cloud;

import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.grim.grimac.api.config.ConfigManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The upload's settings, read from the config and the environment. An empty {@code url} or {@code apiKey}, or no {@code protocols},
 * leaves the upload off. {@code serverIdTemplate} names each socket's server id through {@link CloudServerIds};
 * {@code protocols} are the intake protocols this platform offers, highest last. {@code perf} offers the service CPU and
 * allocation profiles of this process; {@code team} offers team sync, the portal roles of this process's players.
 */
public record CloudSettings(
        String url,
        String apiKey,
        String serverIdTemplate,
        String proxyId,
        List<Integer> protocols,
        boolean perf,
        boolean team) {
    static final String TEAM_ENV = "CULTAC_CLOUD_TEAM_SYNC";
    public static final String DEFAULT_SERVER_ID = "{proxy}.{server}";
    /** A server's default: its own name, since no proxy shares its key. */
    public static final String OWN_SERVER_ID = "{server}";
    /** Every platform's offer: intake protocol 5 alone. */
    public static final List<Integer> PROTOCOLS = List.of(5);

    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
    /** Visible ASCII only, so the key always makes a valid {@code Authorization} header. */
    private static final Pattern API_KEY = Pattern.compile("[\\x21-\\x7E]+");

    public static final CloudSettings OFF = new CloudSettings("", "", DEFAULT_SERVER_ID, "", List.of(), false, false);

    public CloudSettings {
        url = url == null ? "" : url.strip().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.strip();
        serverIdTemplate =
                serverIdTemplate == null || serverIdTemplate.isBlank() ? DEFAULT_SERVER_ID : serverIdTemplate.strip();
        proxyId = proxyId == null ? "" : proxyId.strip();
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
    }

    /**
     * Reads {@code cloud.url}, {@code cloud.api-key}, {@code cloud.server-id}, {@code cloud.proxy-id},
     * {@code cloud.perf} and {@code cloud.team-sync}, an empty {@code cloud.server-id} picking {@code defaultServerId}. {@code CULTAC_CLOUD_URL}
     * and {@code CULTAC_CLOUD_API_KEY} win over the config; an empty {@code cloud.proxy-id} takes
     * {@code CULTAC_PROXY_ID}.
     */
    public static CloudSettings read(ConfigManager config, List<Integer> protocols, String defaultServerId) {
        return read(config, protocols, defaultServerId, System::getenv);
    }

    static CloudSettings read(
            ConfigManager config, List<Integer> protocols, String defaultServerId, Function<String, String> env) {
        String serverId = config.getStringElse("cloud.server-id", "");
        String url = url(config, env);
        if (!url.isEmpty() && !allowedUrl(url)) {
            LogUtil.warn("Cloud upload off: cloud.url must be https, or http to a loopback host, with a host and no"
                    + " user info, query or fragment");
            url = "";
        }
        String proxyId = config.getStringElse("cloud.proxy-id", "");
        if (proxyId == null || proxyId.isBlank()) proxyId = env.apply("CULTAC_PROXY_ID");
        String apiKey = CloudModule.apiKey(config, env);
        if (apiKey != null
                && !apiKey.isBlank()
                && !API_KEY.matcher(apiKey.strip()).matches()) {
            LogUtil.warn("Cloud upload off: cloud.api-key has characters that cannot be sent in an HTTP header;"
                    + " copy the key again");
            apiKey = "";
        }
        return new CloudSettings(
                url,
                apiKey,
                serverId.isBlank() ? defaultServerId : serverId,
                proxyId,
                protocols,
                perf(config, env),
                team(config, env));
    }

    /** The service's address: {@code CULTAC_CLOUD_URL}, else {@code cloud.url}; empty is off. */
    static String url(ConfigManager config, Function<String, String> env) {
        return CloudModule.setting(config, env, "cloud.url", "CULTAC_CLOUD_URL", "");
    }

    /**
     * Whether connect offers perf profiles: {@code CULTAC_CLOUD_PERF} when it is true/1/yes or false/0/no, else
     * {@code cloud.perf}, off by default.
     */
    static boolean perf(ConfigManager config, Function<String, String> env) {
        return flag(config, env, "cloud.perf", "CULTAC_CLOUD_PERF");
    }

    /**
     * Whether connect offers team sync: {@value #TEAM_ENV} when it is true/1/yes or false/0/no, else
     * {@code cloud.team-sync}, off by default.
     */
    static boolean team(ConfigManager config, Function<String, String> env) {
        return flag(config, env, "cloud.team-sync", TEAM_ENV);
    }

    private static boolean flag(ConfigManager config, Function<String, String> env, String key, String envName) {
        String value = env.apply(envName);
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes" -> true;
            case "false", "0", "no" -> false;
            default -> config.getBooleanElse(key, false);
        };
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

    /** Every setting but the key, which is only ever sent in the {@code Authorization} header. */
    @Override
    public String toString() {
        return "CloudSettings[url=" + url + ", apiKey=" + (apiKey.isEmpty() ? "" : "<set>") + ", serverIdTemplate="
                + serverIdTemplate + ", proxyId=" + proxyId + ", protocols=" + protocols + ", perf=" + perf + ", team=" + team + "]";
    }
}
