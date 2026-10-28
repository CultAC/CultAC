package ac.cult.cultac.network.routing;

import ac.grim.grimac.api.config.ConfigManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The config's {@code network} block, each key overridden by its {@code CULTAC_NETWORK_*} environment variable when
 * that is set and not blank. {@code cloudProxyId} is {@code cloud.proxy-id} (or {@code CULTAC_PROXY_ID}), which an
 * empty {@code instanceId} may fall back on. Records compare equal when a reload needs no reconnect.
 */
public record NetworkSettings(
        String transport,
        String instanceId,
        String network,
        String secret,
        String redisUri,
        List<String> natsServers,
        String natsToken,
        String natsCredentialsFile,
        String cloudProxyId) {
    public static final String NONE = "none";
    public static final String REDIS = "redis";
    public static final String NATS = "nats";
    public static final String REDISBUNGEE = "redisbungee";
    /** The {@code to} of a message for every instance, and the last part of the broadcast channel. */
    public static final String ALL = "all";

    public static final NetworkSettings OFF = new NetworkSettings(NONE, "", "default", "", "", List.of(), "", "", "");

    public NetworkSettings {
        transport = transport == null || transport.isBlank()
                ? NONE
                : transport.strip().toLowerCase(Locale.ROOT);
        instanceId = strip(instanceId);
        network = network == null || network.isBlank() ? "default" : network.strip();
        secret = strip(secret);
        redisUri = strip(redisUri);
        natsServers = natsServers == null
                ? List.of()
                : natsServers.stream()
                        .filter(server -> server != null && !server.isBlank())
                        .map(String::strip)
                        .toList();
        natsToken = strip(natsToken);
        natsCredentialsFile = strip(natsCredentialsFile);
        cloudProxyId = strip(cloudProxyId);
    }

    public static NetworkSettings read(ConfigManager config) {
        return read(config, System::getenv);
    }

    static NetworkSettings read(ConfigManager config, Function<String, String> env) {
        String servers = env.apply("CULTAC_NETWORK_NATS_SERVERS");
        List<String> natsServers = servers == null || servers.isBlank()
                ? config.getStringListElse("network.nats.servers", List.of())
                : Arrays.asList(servers.split(","));
        String cloudProxyId = config.getStringElse("cloud.proxy-id", "");
        if (cloudProxyId == null || cloudProxyId.isBlank()) cloudProxyId = env.apply("CULTAC_PROXY_ID");
        return new NetworkSettings(
                setting(config, env, "network.transport", "CULTAC_NETWORK_TRANSPORT", NONE),
                setting(config, env, "network.instance-id", "CULTAC_NETWORK_INSTANCE_ID", ""),
                setting(config, env, "network.network", "CULTAC_NETWORK_NETWORK", "default"),
                setting(config, env, "network.secret", "CULTAC_NETWORK_SECRET", ""),
                setting(config, env, "network.redis.uri", "CULTAC_NETWORK_REDIS_URI", ""),
                natsServers,
                setting(config, env, "network.nats.token", "CULTAC_NETWORK_NATS_TOKEN", ""),
                setting(config, env, "network.nats.credentials-file", "CULTAC_NETWORK_NATS_CREDENTIALS_FILE", ""),
                cloudProxyId);
    }

    private static String setting(
            ConfigManager config, Function<String, String> env, String key, String envName, String fallback) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) value = config.getStringElse(key, fallback);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** Whether {@link #transport} names a transport; {@code none} included. */
    public boolean knownTransport() {
        return switch (transport) {
            case NONE, REDIS, NATS, REDISBUNGEE -> true;
            default -> false;
        };
    }

    /**
     * This instance's id: {@code instance-id}, else RedisBungee's proxy id, else {@link #cloudProxyId}, else
     * {@code address}. Each supplier runs only when the ones before it named nothing.
     */
    public String resolveInstanceId(Supplier<@Nullable String> redisBungeeId, Supplier<String> address) {
        if (!instanceId.isEmpty()) return instanceId;
        String proxyId = redisBungeeId.get();
        if (proxyId != null && !proxyId.isBlank()) return proxyId.strip();
        if (!cloudProxyId.isEmpty()) return cloudProxyId;
        return address.get();
    }

    /** Why {@code id} cannot name an instance in a channel, or null when it can. */
    public static @Nullable String invalidInstanceId(String id) {
        if (id.isEmpty()) return "it is empty";
        if (id.equalsIgnoreCase(ALL)) return "\"" + ALL + "\" names the broadcast channel";
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '*' || c == '>')
                return "it has a space, a control character, '*' or '>'";
        }
        return null;
    }

    /** The key messages are signed with: {@code secret}, else {@code fallback}; null when both are empty. */
    public byte @Nullable [] resolveSecret(byte @Nullable [] fallback) {
        if (!secret.isEmpty()) return secret.getBytes(StandardCharsets.UTF_8);
        return fallback == null || fallback.length == 0 ? null : fallback.clone();
    }

    /** {@code cultac.<network>.<instance>}. */
    public String channel(String instance) {
        return "cultac." + network + "." + instance;
    }

    private static String strip(@Nullable String value) {
        return value == null ? "" : value.strip();
    }

    /** Every setting, with the secret, the token and the Redis password left out. */
    @Override
    public String toString() {
        return "NetworkSettings[transport=" + transport + ", instanceId=" + instanceId + ", network=" + network
                + ", secret=" + (secret.isEmpty() ? "" : "<set>") + ", redisUri=" + redactedUri(redisUri)
                + ", natsServers="
                + natsServers.stream().map(NetworkSettings::redactedUri).toList()
                + ", natsToken=" + (natsToken.isEmpty() ? "" : "<set>") + ", natsCredentialsFile="
                + natsCredentialsFile + ", cloudProxyId=" + cloudProxyId + "]";
    }

    /** {@code uri} without its user info. */
    static String redactedUri(String uri) {
        if (uri.isEmpty()) return uri;
        try {
            URI parsed = new URI(uri);
            if (parsed.getRawUserInfo() == null) return uri;
            return new URI(
                            parsed.getScheme(),
                            "<redacted>",
                            parsed.getHost(),
                            parsed.getPort(),
                            parsed.getPath(),
                            parsed.getQuery(),
                            parsed.getFragment())
                    .toString();
        } catch (URISyntaxException malformed) {
            return "<unparsable>";
        }
    }
}
