package ac.cult.cultac.cloud.transport;

import ac.cult.cultac.cloud.CloudSettings;
import ac.cult.cultac.cloud.wire.IntakeWire;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The process's intake sockets, one per (service, key, server id). A socket opens when a server id is first asked for
 * and closes once no stream has used it for a minute. Every socket shares one HTTP client and one compression pool,
 * made when the upload is configured on.
 */
public final class CloudConnections {
    private record Key(String url, String apiKey, String serverId) {}

    private final CloudConnection.Timing timing;
    private final Map<Key, CloudConnection> connections = new ConcurrentHashMap<>();
    private final CloudConnection.Owner owner = new CloudConnection.Owner() {
        @Override
        public void idle(CloudConnection connection) {
            closeIdle(connection);
        }

        @Override
        public void unavailable(CloudConnection connection, String why) {
            turnOff(connection, why);
        }

        @Override
        public void finding(IntakeWire.Event event) {
            findings.accept(event);
        }
    };
    private volatile CloudSettings settings = CloudSettings.OFF;
    /** The transport the open sockets share; made when the current settings turn the upload on. */
    private SharedTransport shared;
    /** Why the upload stays off until the next {@link #configure}, once the service or zstd has ruled it out. */
    private volatile String unavailable;
    /** A newer runtime is taking over: no socket opens again. */
    private volatile boolean frozen;

    private volatile Runnable turnedOff = () -> {};
    private volatile Consumer<IntakeWire.Event> findings = event -> {};

    public CloudConnections() {
        this(CloudConnection.Timing.DEFAULT);
    }

    /** Sockets on {@code timing}'s periods and clock. */
    CloudConnections(CloudConnection.Timing timing) {
        this.timing = timing;
    }

    /**
     * Applies {@code settings}. Sockets on another service, key or protocol offer close; the rest
     * reconnect if the agent they announced has changed. With the upload on, zstd is loaded and the shared transport
     * made here, so {@link #forServer} does map work only.
     */
    public void configure(CloudSettings settings) {
        String zstdUnavailable = settings.enabled() ? IntakeWire.zstdUnavailable() : null;
        boolean offerable = !settings.enabled()
                || !CloudConnection.protocols(settings.protocols(), zstdUnavailable)
                        .isEmpty();
        List<CloudConnection> closing;
        synchronized (this) {
            CloudSettings previous = this.settings;
            this.settings = settings;
            unavailable = null;
            boolean same = settings.enabled()
                    && shared != null
                    && previous.url().equals(settings.url())
                    && previous.apiKey().equals(settings.apiKey())
                    && previous.protocols().equals(settings.protocols());
            if (same) {
                for (CloudConnection connection : connections.values()) connection.reloaded();
                return;
            }
            closing = detachAll();
            if (settings.enabled() && offerable) shared = new SharedTransport();
        }
        closing.forEach(CloudConnection::close);
        if (!offerable) turnOff(null, "intake protocol 5 needs zstd, which did not load: " + zstdUnavailable);
    }

    public CloudSettings settings() {
        return settings;
    }

    /** Whether the upload is configured on and nothing has turned it off since. */
    public boolean available() {
        return settings.enabled() && unavailable == null && !frozen;
    }

    /**
     * Runs {@code hook}, in place of any earlier one, each time the service or zstd turns the upload off. It runs on the
     * thread that turned it off, outside any lock here.
     */
    public void onTurnedOff(Runnable hook) {
        turnedOff = hook;
    }

    /** Runs {@code hook}, in place of any earlier one, on each finding the service sends, on the socket's thread. */
    public void onFinding(Consumer<IntakeWire.Event> hook) {
        findings = hook;
    }

    /**
     * The socket for server id {@code id}, opened on first use; null while the upload is off. Each call starts the
     * socket's idle period again.
     */
    public @Nullable CloudConnection forServer(String id) {
        synchronized (this) {
            CloudSettings current = settings;
            if (!current.enabled() || unavailable != null || frozen || shared == null) return null;
            Key key = new Key(current.url(), current.apiKey(), id);
            CloudConnection connection = connections.get(key);
            if (connection != null) {
                connection.touch();
                return connection;
            }
            connection = new CloudConnection(
                    URI.create(current.url() + "/api/v1/connect"),
                    current.apiKey(),
                    id,
                    current.protocols(),
                    shared,
                    owner,
                    timing);
            connections.put(key, connection);
            return connection;
        }
    }

    /** A segment on server id {@code id} left its socket: the socket's idle period starts again. */
    public void release(String id) {
        for (Map.Entry<Key, CloudConnection> entry : connections.entrySet()) {
            if (entry.getKey().serverId().equals(id)) entry.getValue().touch();
        }
    }

    /** Plugin disable: every socket closes without blocking, and the upload stays off. */
    public void closeAll() {
        List<CloudConnection> closing;
        synchronized (this) {
            settings = CloudSettings.OFF;
            closing = detachAll();
        }
        closing.forEach(CloudConnection::close);
    }

    /**
     * A newer runtime is taking over: no socket opens again and no new one is handed out, while open ones stay to
     * send what is queued. Does not block.
     */
    public void freeze() {
        List<CloudConnection> frozen;
        synchronized (this) {
            this.frozen = true;
            frozen = new ArrayList<>(connections.values());
        }
        frozen.forEach(CloudConnection::freeze);
    }

    /**
     * A newer runtime took over: every socket sends what is queued, closes normally and never reconnects, and the
     * upload stays off. Does not block.
     */
    public void retireAll() {
        List<CloudConnection> retiring;
        synchronized (this) {
            settings = CloudSettings.OFF;
            retiring = detachAll();
        }
        retiring.forEach(CloudConnection::retire);
    }

    /** One line per socket: what is queued, being compressed and refused; empty without one. */
    public List<String> summaries() {
        List<String> lines = new ArrayList<>();
        for (CloudConnection connection : connections.values()) lines.add(connection.summary());
        return lines;
    }

    /** One diagnostics line per socket: counts only, never whom. */
    public List<String> diagnosticsSummaries() {
        List<String> lines = new ArrayList<>();
        for (CloudConnection connection : connections.values()) lines.add(connection.diagnosticsSummary());
        return lines;
    }

    int size() {
        return connections.size();
    }

    private void closeIdle(CloudConnection connection) {
        synchronized (this) {
            if (!connection.idle() || !connections.values().remove(connection)) return;
        }
        connection.close();
    }

    /**
     * Turns the upload off until the next {@link #configure}, with one warning. A {@code connection} the registry no
     * longer holds speaks for settings already replaced, and is ignored.
     */
    private void turnOff(@Nullable CloudConnection connection, String why) {
        List<CloudConnection> closing;
        synchronized (this) {
            if (unavailable != null) return;
            if (connection != null && !connections.containsValue(connection)) return;
            unavailable = why;
            closing = detachAll();
        }
        LogUtil.warn("Cloud upload off until the config is reloaded: " + why);
        closing.forEach(CloudConnection::close);
        try {
            turnedOff.run();
        } catch (RuntimeException failure) {
            LogUtil.warn("Cloud upload turn-off hook failed", failure);
        }
    }

    /** Empties the registry and retires the shared transport; the caller closes what is returned outside the lock. */
    private List<CloudConnection> detachAll() {
        List<CloudConnection> detached = new ArrayList<>(connections.values());
        connections.clear();
        if (shared != null) shared.retire();
        shared = null;
        return detached;
    }
}
