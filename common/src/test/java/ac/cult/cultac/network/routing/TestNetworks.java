package ac.cult.cultac.network.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.reload.api.HostContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Network services on one in-memory broker, each running every message on the caller's thread unless given executors,
 * and the fixtures they share: the signing key and settings, a fake platform and a fake host.
 */
public final class TestNetworks {
    public static final byte[] KEY = "a shared network secret of some length".getBytes(StandardCharsets.UTF_8);
    static final NetworkSettings SETTINGS = new NetworkSettings("redis://cache:6379", "", "", "", "");

    private final FakeBroker broker;
    private final AtomicLong clock;

    public TestNetworks() {
        this(new FakeBroker(), new AtomicLong(1_793_140_000_000L));
    }

    TestNetworks(FakeBroker broker, AtomicLong clock) {
        this.broker = broker;
        this.clock = clock;
    }

    /** The channel {@code instance} receives on under {@link #KEY}; {@value NetworkSettings#ALL} is the broadcast. */
    static String channel(String instance) {
        return NetworkSettings.channel(KEY, instance);
    }

    /** Makes {@code service} the one {@code NetworkRouting.current()} returns, until {@link NetworkModule#stop}. */
    public static void install(NetworkService service) {
        NetworkModule.stop();
        NetworkModule.start(service);
    }

    /**
     * A platform reporting the players put online in that order, each held by this generation unless {@link #holders}
     * says else.
     */
    public static final class Players implements NetworkPlatform {
        public final Map<UUID, OnlinePlayer> online = Collections.synchronizedMap(new LinkedHashMap<>());
        public final Map<UUID, Holder> holders = new ConcurrentHashMap<>();
        private String address = "test:1";
        private boolean proxy;
        private @Nullable String redisBungeeId;
        private byte @Nullable [] secret;
        private @Nullable Supplier<MessageTransport> redisBungee;

        public Players with(UUID uuid, String name, @Nullable String server) {
            online.put(uuid, new OnlinePlayer(uuid, name, server));
            return this;
        }

        /** {@code uuid} online on the {@code lobby} server. */
        public Players with(UUID uuid, String name) {
            return with(uuid, name, "lobby");
        }

        /** A proxy at {@code proxy:25577}, RedisBungee's id for it {@code redisBungeeId} (null without RedisBungee). */
        public Players proxy(@Nullable String redisBungeeId) {
            this.proxy = true;
            this.address = "proxy:25577";
            this.redisBungeeId = redisBungeeId;
            return this;
        }

        public Players secret(byte @Nullable [] secret) {
            this.secret = secret;
            return this;
        }

        public Players redisBungee(Supplier<MessageTransport> transport) {
            this.redisBungee = transport;
            return this;
        }

        @Override
        public Collection<OnlinePlayer> onlinePlayers() {
            synchronized (online) {
                return List.copyOf(online.values());
            }
        }

        @Override
        public Holder holder(UUID uuid) {
            return holders.getOrDefault(uuid, Holder.THIS);
        }

        @Override
        public String address() {
            return address;
        }

        @Override
        public boolean proxy() {
            return proxy;
        }

        @Override
        public @Nullable String redisBungeeId() {
            return redisBungeeId;
        }

        @Override
        public byte @Nullable [] defaultSecret() {
            return secret;
        }

        @Override
        public @Nullable MessageTransport redisBungee() {
            return redisBungee == null ? null : redisBungee.get();
        }
    }

    /** A host whose generations share {@code shared}, this one {@code generation}, current while {@code current}. */
    @SuppressWarnings("unchecked")
    public static HostContext host(Map<String, Object> shared, Callable<Boolean> current, String generation) {
        HostContext host = mock(HostContext.class);
        when(host.generationId()).thenReturn(generation);
        when(host.current()).thenAnswer(invocation -> current.call());
        when(host.shared(anyString(), any()))
                .thenAnswer(invocation -> shared.computeIfAbsent(
                        invocation.getArgument(0), key -> ((Supplier<Object>) invocation.getArgument(1)).get()));
        return host;
    }

    public static HostContext host(Map<String, Object> shared, boolean current) {
        return host(shared, () -> current, UUID.randomUUID().toString());
    }

    /** A service for {@code instance} on this broker, by default with no players, no host and the shared key. */
    public Builder service(String instance) {
        return new Builder(instance);
    }

    public NetworkService started(String instance) {
        return service(instance).start();
    }

    /** A service with no transport: only the in-process bus. */
    public NetworkService withoutTransport(String instance) {
        return service(instance).transport(null).start();
    }

    /** Runs {@code service}'s periodic roster tick now. */
    public void tick(NetworkService service) {
        service.tick();
    }

    /** Stops {@code service}, which sends its goodbye unless a later generation took over. */
    public void stop(NetworkService service) {
        service.stop();
    }

    /** The body of every {@code type} message broadcast so far, oldest first. */
    public List<JsonObject> broadcasts(String type) {
        List<JsonObject> bodies = new ArrayList<>();
        for (FakeBroker.Published message : broker.on(channel(NetworkSettings.ALL))) {
            String text = message.text();
            JsonObject envelope = JsonParser.parseString(text.substring(text.indexOf('.') + 1))
                    .getAsJsonObject();
            if (envelope.get("type").getAsString().equals(type)) bodies.add(envelope.getAsJsonObject("body"));
        }
        return bodies;
    }

    public final class Builder {
        private final String instance;
        private NetworkPlatform platform = new Players();
        private @Nullable HostContext host;
        private byte @Nullable [] key = KEY;
        private @Nullable MessageTransport transport = broker.transport();
        private @Nullable ScheduledExecutorService scheduler;
        private @Nullable ExecutorService receiver;
        private LogThrottle drops = new LogThrottle();
        private RemoteCommands.Commands commands = RemoteCommands.CULT;

        private Builder(String instance) {
            this.instance = instance;
        }

        public Builder platform(NetworkPlatform platform) {
            this.platform = platform;
            return this;
        }

        public Builder host(@Nullable HostContext host) {
            this.host = host;
            return this;
        }

        public Builder key(byte @Nullable [] key) {
            this.key = key;
            return this;
        }

        /** Null leaves the service with only the in-process bus. */
        public Builder transport(@Nullable MessageTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder scheduler(@Nullable ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder receiver(@Nullable ExecutorService receiver) {
            this.receiver = receiver;
            return this;
        }

        public Builder drops(LogThrottle drops) {
            this.drops = drops;
            return this;
        }

        public Builder commands(RemoteCommands.Commands commands) {
            this.commands = commands;
            return this;
        }

        /** Commands that add each line run here to {@code ran} and do nothing else. */
        public Builder recording(List<String> ran) {
            return commands(new RemoteCommands.Commands() {
                @Override
                public CompletionStage<?> execute(Sender sender, String line) {
                    ran.add(line);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public Set<String> permissionNodes() {
                    return Set.of();
                }
            });
        }

        public NetworkService build() {
            return new NetworkService(
                    SETTINGS,
                    platform,
                    host,
                    instance,
                    key,
                    transport,
                    clock::get,
                    scheduler,
                    receiver,
                    drops,
                    commands);
        }

        public NetworkService start() {
            NetworkService service = build();
            service.start();
            return service;
        }
    }
}
