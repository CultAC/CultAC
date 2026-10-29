package ac.cult.cultac.network.routing;

import ac.grim.grimac.api.config.ConfigManager;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

/**
 * The config's {@code network} block, each key overridden by its non-blank {@code CULTAC_NETWORK_*} environment
 * variable, and {@code cloud.proxy-id}. {@code type} picks the broker; only its block is read, the other one holds
 * its defaults. Records compare equal when a reload needs no reconnect.
 */
public record NetworkSettings(
        String type, Redis redis, Nats nats, String secret, String instanceId, String cloudProxyId) {
    public static final String NONE = "none";
    public static final String REDIS = "redis";
    public static final String NATS = "nats";
    public static final String REDISBUNGEE = "redisbungee";
    /** The {@code to} of a message for every instance, and the last part of the broadcast channel. */
    public static final String ALL = "all";
    /** The values {@code network.type} accepts, for the error naming them. */
    static final String ACCEPTED = "none, redis, nats or redisbungee";

    public static final NetworkSettings OFF = new NetworkSettings(NONE, Redis.DEFAULT, Nats.DEFAULT, "", "", "");

    /** The message a broker credential is signed over to derive the signing key from it. */
    private static final byte[] DERIVATION = "cultac-network-signing".getBytes(StandardCharsets.UTF_8);

    /** What one part of a channel name may hold, on every broker. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");

    private static final Pattern NOT_TOKEN = Pattern.compile("[^A-Za-z0-9_-]");

    /** The {@code network.redis} block. */
    public record Redis(String address, String username, String password, boolean ssl) {
        public static final int DEFAULT_PORT = 6379;
        public static final Redis DEFAULT = new Redis("localhost:" + DEFAULT_PORT, "", "", false);

        public Redis {
            address = strip(address);
            username = raw(username);
            password = raw(password);
        }

        /**
         * The one server {@link #address} names.
         *
         * @throws IllegalArgumentException when it names none, or several
         */
        public Address server() {
            if (address.contains(",") || address.contains("://"))
                throw new IllegalArgumentException(
                        "network.redis.address takes one host[:port], without a scheme or a second server");
            return Address.parse(address, DEFAULT_PORT, "network.redis.address");
        }

        /** The password, or {@code user:password} with a username, the way a Redis URI's user info gave it. */
        @Nullable
        String credential() {
            return NetworkSettings.credential(username, password);
        }

        @Override
        public String toString() {
            return "Redis[address=" + address + ", username=" + set(username) + ", password=" + set(password) + ", ssl="
                    + ssl + "]";
        }
    }

    /** The {@code network.nats} block. */
    public record Nats(
            String address, String username, String password, String token, String credentialsFile, boolean ssl) {
        public static final int DEFAULT_PORT = 4222;
        public static final Nats DEFAULT = new Nats("localhost:" + DEFAULT_PORT, "", "", "", "", false);

        public Nats {
            address = strip(address);
            username = raw(username);
            password = raw(password);
            token = raw(token);
            credentialsFile = strip(credentialsFile);
        }

        /**
         * A {@code nats://} URL, {@code tls://} with {@link #ssl}, for each server {@link #address} lists.
         *
         * @throws IllegalArgumentException when it lists none, or one that is not {@code host[:port]}
         */
        public List<String> servers() {
            String scheme = ssl ? "tls://" : "nats://";
            List<String> servers = new ArrayList<>();
            for (String server : address.split(",")) {
                String stripped = server.strip();
                if (stripped.isEmpty()) continue;
                if (stripped.contains("://"))
                    throw new IllegalArgumentException(
                            "network.nats.address takes host[:port] servers without a scheme; set ssl for tls://");
                servers.add(scheme + Address.parse(stripped, DEFAULT_PORT, "network.nats.address"));
            }
            if (servers.isEmpty()) throw new IllegalArgumentException("network.nats.address names no server");
            return servers;
        }

        /** The token, else the password or {@code user:password}, the way a NATS URI's user info gave it. */
        @Nullable
        String credential() {
            if (token.isEmpty()) return NetworkSettings.credential(username, password);
            String credential = token.startsWith(":") ? token.substring(1) : token;
            return credential.isEmpty() ? null : credential;
        }

        @Override
        public String toString() {
            return "Nats[address=" + address + ", username=" + set(username) + ", password=" + set(password)
                    + ", token=" + set(token) + ", credentialsFile=" + credentialsFile + ", ssl=" + ssl + "]";
        }
    }

    /** A {@code host[:port]}; an IPv6 host goes in brackets. */
    public record Address(String host, int port) {
        /**
         * {@code text} as {@code host[:port]}, {@code defaultPort} when it names none.
         *
         * @throws IllegalArgumentException naming {@code key} when it is not one
         */
        static Address parse(String text, int defaultPort, String key) {
            String host;
            String port;
            if (text.startsWith("[")) {
                int close = text.indexOf(']');
                if (close < 0) throw new IllegalArgumentException(key + " opens an IPv6 bracket it never closes");
                host = text.substring(1, close);
                String rest = text.substring(close + 1);
                if (!rest.isEmpty() && !rest.startsWith(":"))
                    throw new IllegalArgumentException(key + " must be host[:port]");
                port = rest.isEmpty() ? null : rest.substring(1);
            } else {
                int colon = text.indexOf(':');
                if (colon != text.lastIndexOf(':'))
                    throw new IllegalArgumentException(key + " holds an IPv6 address; put it in brackets: [::1]:port");
                host = colon < 0 ? text : text.substring(0, colon);
                port = colon < 0 ? null : text.substring(colon + 1);
            }
            if (host.isEmpty() || host.chars().anyMatch(c -> Character.isWhitespace(c) || c == '@' || c == '/'))
                throw new IllegalArgumentException(key + " must be host[:port]");
            return new Address(host, port == null ? defaultPort : port(port, key));
        }

        private static int port(String text, String key) {
            try {
                int port = Integer.parseInt(text);
                if (port >= 1 && port <= 65535) return port;
            } catch (NumberFormatException notANumber) {
                // Reported below.
            }
            throw new IllegalArgumentException(key + " has a port outside 1-65535");
        }

        @Override
        public String toString() {
            return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        }
    }

    public NetworkSettings {
        type = strip(type).toLowerCase(Locale.ROOT);
        if (type.isEmpty()) type = NONE;
        if (!type.equals(REDIS) || redis == null) redis = Redis.DEFAULT;
        if (!type.equals(NATS) || nats == null) nats = Nats.DEFAULT;
        secret = strip(secret);
        instanceId = strip(instanceId);
        cloudProxyId = strip(cloudProxyId);
    }

    public static NetworkSettings read(ConfigManager config) {
        return read(config, System::getenv);
    }

    static NetworkSettings read(ConfigManager config, Function<String, String> env) {
        String cloudProxyId = config.getStringElse("cloud.proxy-id", "");
        if (cloudProxyId == null || cloudProxyId.isBlank()) cloudProxyId = env.apply("CULTAC_PROXY_ID");
        String type = setting(config, env, "network.type", "CULTAC_NETWORK_TYPE");
        String chosen = type.toLowerCase(Locale.ROOT);
        Redis redis = chosen.equals(REDIS)
                ? new Redis(
                        setting(config, env, "network.redis.address", "CULTAC_NETWORK_REDIS_ADDRESS"),
                        credential(config, env, "network.redis.username", "CULTAC_NETWORK_REDIS_USERNAME"),
                        credential(config, env, "network.redis.password", "CULTAC_NETWORK_REDIS_PASSWORD"),
                        flag(config, env, "network.redis.ssl", "CULTAC_NETWORK_REDIS_SSL"))
                : Redis.DEFAULT;
        Nats nats = chosen.equals(NATS)
                ? new Nats(
                        setting(config, env, "network.nats.address", "CULTAC_NETWORK_NATS_ADDRESS"),
                        credential(config, env, "network.nats.username", "CULTAC_NETWORK_NATS_USERNAME"),
                        credential(config, env, "network.nats.password", "CULTAC_NETWORK_NATS_PASSWORD"),
                        credential(config, env, "network.nats.token", "CULTAC_NETWORK_NATS_TOKEN"),
                        setting(config, env, "network.nats.credentials-file", "CULTAC_NETWORK_NATS_CREDENTIALS_FILE"),
                        flag(config, env, "network.nats.ssl", "CULTAC_NETWORK_NATS_SSL"))
                : Nats.DEFAULT;
        return new NetworkSettings(
                type,
                redis,
                nats,
                setting(config, env, "network.secret", "CULTAC_NETWORK_SECRET"),
                setting(config, env, "network.instance-id", "CULTAC_NETWORK_INSTANCE_ID"),
                cloudProxyId);
    }

    private static String setting(ConfigManager config, Function<String, String> env, String key, String envName) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) value = config.getStringElse(key, "");
        return value == null ? "" : value.strip();
    }

    /** As {@link #setting}, keeping the spaces a password or token may start or end with. */
    private static String credential(ConfigManager config, Function<String, String> env, String key, String envName) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) value = config.getStringElse(key, "");
        return value == null ? "" : value;
    }

    private static boolean flag(ConfigManager config, Function<String, String> env, String key, String envName) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) return config.getBooleanElse(key, false);
        return Boolean.parseBoolean(value.strip());
    }

    /** The broker {@link #type} names; null for a value outside {@value #ACCEPTED}. */
    public @Nullable String transport() {
        return switch (type) {
            case NONE, REDIS, NATS, REDISBUNGEE -> type;
            default -> null;
        };
    }

    /** The chosen broker's password, token or {@code user:password}; null without one. */
    @Nullable
    String credential() {
        return switch (type) {
            case REDIS -> redis.credential();
            case NATS -> nats.credential();
            default -> null;
        };
    }

    private static @Nullable String credential(String username, String password) {
        if (username.isEmpty()) return password.isEmpty() ? null : password;
        return username + ":" + password;
    }

    /** {@code instance-id}, else RedisBungee's proxy id, else {@link #cloudProxyId}, else {@code address} made safe. */
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

    /** {@code secret}, else {@code platformSecret}, else a key derived from {@link #credential}; null without any. */
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

    /** {@code cultac.<8 hex digits of SHA-256(key)>.<instance>}: only instances sharing a key share channels. */
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

    private static String raw(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static String set(String value) {
        return value.isEmpty() ? "" : "<set>";
    }

    /** Every setting of the chosen broker, each credential and the secret shown only as set or not. */
    @Override
    public String toString() {
        String block = switch (type) {
            case REDIS -> ", redis=" + redis;
            case NATS -> ", nats=" + nats;
            default -> "";
        };
        return "NetworkSettings[type=" + type + block + ", secret=" + set(secret) + ", instanceId=" + instanceId
                + ", cloudProxyId=" + cloudProxyId + "]";
    }
}
