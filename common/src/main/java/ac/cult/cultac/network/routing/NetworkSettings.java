package ac.cult.cultac.network.routing;

import ac.grim.grimac.api.config.ConfigManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

/**
 * The config's {@code network} block, each key overridden by its {@code CULTAC_NETWORK_*} environment variable when
 * that is set and not blank. {@code uri} picks the transport: empty for none, {@code redis://} or {@code rediss://}
 * for Redis, {@code nats://} or {@code tls://} (comma-separated) for NATS, {@code redisbungee} for RedisBungee's own
 * pub/sub. {@code cloudProxyId} is {@code cloud.proxy-id} (or {@code CULTAC_PROXY_ID}), which an empty
 * {@code instanceId} may fall back on. Records compare equal when a reload needs no reconnect.
 */
public record NetworkSettings(
        String uri, String secret, String instanceId, String natsCredentialsFile, String cloudProxyId) {
    public static final String NONE = "none";
    public static final String REDIS = "redis";
    public static final String NATS = "nats";
    public static final String REDISBUNGEE = "redisbungee";
    /** The {@code to} of a message for every instance, and the last part of the broadcast channel. */
    public static final String ALL = "all";
    /** The schemes and values {@code network.uri} accepts, for the error naming them. */
    static final String ACCEPTED = "redis://, rediss://, nats://, tls:// or redisbungee";

    public static final NetworkSettings OFF = new NetworkSettings("", "", "", "", "");

    /** The message a URI credential is signed over to derive the signing key from it. */
    private static final byte[] DERIVATION = "cultac-network-signing".getBytes(StandardCharsets.UTF_8);

    /** What one part of a channel name may hold, on every broker. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern NOT_TOKEN = Pattern.compile("[^A-Za-z0-9_-]");

    private static final Pattern SCHEME = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://");

    public NetworkSettings {
        uri = strip(uri);
        secret = strip(secret);
        instanceId = strip(instanceId);
        natsCredentialsFile = strip(natsCredentialsFile);
        cloudProxyId = strip(cloudProxyId);
    }

    public static NetworkSettings read(ConfigManager config) {
        return read(config, System::getenv);
    }

    static NetworkSettings read(ConfigManager config, Function<String, String> env) {
        String cloudProxyId = config.getStringElse("cloud.proxy-id", "");
        if (cloudProxyId == null || cloudProxyId.isBlank()) cloudProxyId = env.apply("CULTAC_PROXY_ID");
        return new NetworkSettings(
                setting(config, env, "network.uri", "CULTAC_NETWORK_URI"),
                setting(config, env, "network.secret", "CULTAC_NETWORK_SECRET"),
                setting(config, env, "network.instance-id", "CULTAC_NETWORK_INSTANCE_ID"),
                setting(config, env, "network.nats-credentials-file", "CULTAC_NETWORK_NATS_CREDENTIALS_FILE"),
                cloudProxyId);
    }

    private static String setting(ConfigManager config, Function<String, String> env, String key, String envName) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) value = config.getStringElse(key, "");
        return value == null ? "" : value.strip();
    }

    /**
     * {@value #NONE}, {@value #REDIS}, {@value #NATS} or {@value #REDISBUNGEE}, as {@link #uri} names it; null when
     * its scheme is none of {@value #ACCEPTED}, or when NATS servers mix in another scheme.
     */
    public @Nullable String transport() {
        if (uri.isEmpty()) return NONE;
        if (uri.equalsIgnoreCase(REDISBUNGEE)) return REDISBUNGEE;
        List<String> servers = servers();
        String first = scheme(servers.getFirst());
        if (first.equals("redis") || first.equals("rediss")) return servers.size() == 1 ? REDIS : null;
        for (String server : servers) {
            String scheme = scheme(server);
            if (!scheme.equals("nats") && !scheme.equals("tls")) return null;
        }
        return NATS;
    }

    /** The scheme {@link #uri} starts with, lowercased; empty when it has none. */
    String scheme() {
        return uri.isEmpty() ? "" : scheme(servers().getFirst());
    }

    /** Each comma-separated server in {@link #uri}; one for Redis. */
    List<String> servers() {
        return Arrays.stream(uri.split(","))
                .map(String::strip)
                .filter(server -> !server.isEmpty())
                .toList();
    }

    private static String scheme(String server) {
        Matcher scheme = SCHEME.matcher(server);
        return scheme.lookingAt() ? scheme.group(1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * The credential in {@link #uri}'s first user info: a Redis password, or {@code user:password} when it names a
     * user; a NATS token or {@code user:password}. Null when no server carries one.
     */
    @Nullable
    String credential() {
        if (uri.isEmpty() || uri.equalsIgnoreCase(REDISBUNGEE)) return null;
        for (String server : servers()) {
            String userInfo;
            try {
                userInfo = new URI(server).getUserInfo();
            } catch (URISyntaxException malformed) {
                continue;
            }
            if (userInfo == null) continue;
            String credential = userInfo.startsWith(":") ? userInfo.substring(1) : userInfo;
            if (!credential.isEmpty()) return credential;
        }
        return null;
    }

    /**
     * This instance's id: {@code instance-id}, else RedisBungee's proxy id, else {@link #cloudProxyId}, else
     * {@code address} with each character a channel cannot carry made a {@code -}. Each supplier runs only when the
     * ones before it named nothing.
     */
    public String resolveInstanceId(Supplier<@Nullable String> redisBungeeId, Supplier<String> address) {
        if (!instanceId.isEmpty()) return instanceId;
        String proxyId = redisBungeeId.get();
        if (proxyId != null && !proxyId.isBlank()) return proxyId.strip();
        if (!cloudProxyId.isEmpty()) return cloudProxyId;
        return NOT_TOKEN.matcher(address.get()).replaceAll("-");
    }

    /** Why {@code id} cannot name an instance in a channel, or null when it can. */
    public static @Nullable String invalidInstanceId(String id) {
        if (id.equalsIgnoreCase(ALL)) return "\"" + ALL + "\" names the broadcast channel";
        if (id.isEmpty()) return "it is empty";
        if (!TOKEN.matcher(id).matches()) return "it holds a character other than A-Z, a-z, 0-9, '_' and '-'";
        return null;
    }

    /**
     * The key messages are signed with: {@code secret}, else {@code platformSecret} (the forwarding secret the proxy
     * and its backends share), else HMAC-SHA256 of {@code "cultac-network-signing"} keyed by {@link #credential}; null
     * when none of them is set.
     */
    public byte @Nullable [] resolveSecret(byte @Nullable [] platformSecret) {
        if (!secret.isEmpty()) return secret.getBytes(StandardCharsets.UTF_8);
        if (platformSecret != null && platformSecret.length > 0) return platformSecret.clone();
        String credential = credential();
        return credential == null ? null : derive(credential);
    }

    private static byte[] derive(String credential) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(credential.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(DERIVATION);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    /**
     * {@code cultac.<namespace>.<instance>}, the namespace the first 8 hex digits of the SHA-256 of {@code key}: only
     * instances that can verify each other share channels.
     */
    public static String channel(byte @Nullable [] key, String instance) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key == null ? new byte[0] : key);
            return "cultac." + HexFormat.of().formatHex(digest, 0, 4) + "." + instance;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String strip(@Nullable String value) {
        return value == null ? "" : value.strip();
    }

    /** Every setting, with the secret and the user info of each server in {@link #uri} left out. */
    @Override
    public String toString() {
        String servers = REDISBUNGEE.equalsIgnoreCase(uri)
                ? uri
                : String.join(",", servers().stream().map(Redaction::url).toList());
        return "NetworkSettings[uri=" + servers + ", secret=" + (secret.isEmpty() ? "" : "<set>") + ", instanceId="
                + instanceId + ", natsCredentialsFile=" + natsCredentialsFile + ", cloudProxyId=" + cloudProxyId + "]";
    }
}
