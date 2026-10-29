package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis pub/sub through Jedis. One connection subscribes on its own thread, reconnecting with a capped backoff; it
 * reads without a timeout, so a watchdog pings it and drops it when no pong comes back in time. A second connection
 * publishes from one worker thread behind a {@link Backlog}. Closing drains the backlog briefly, then interrupts the
 * worker, which ends at the latest when a connect in progress times out.
 */
final class RedisTransport implements MessageTransport {
    static final int DEFAULT_PORT = 6379;
    static final long MIN_BACKOFF_MILLIS = 1_000;
    static final long MAX_BACKOFF_MILLIS = 30_000;

    /** How long a connect and a read may take, how often the watchdog pings, and how long closing drains. */
    record Timings(int connectMillis, int readMillis, long pingMillis, long pongTimeoutMillis, long drainMillis) {
        static final Timings DEFAULT = new Timings(10_000, 10_000, 10_000, 30_000, 2_000);
    }

    private final URI uri;
    private final Redaction redaction;
    private final Timings timings;
    /** Publishes in order on one thread, which closes its connection when it ends. */
    private final ExecutorService worker;

    private final ScheduledExecutorService watchdog;
    private final Backlog backlog = new Backlog(
            "Network transport Redis: dropped a message to publish; " + MAX_BACKLOG_MESSAGES + " messages or "
                    + MAX_BACKLOG_BYTES / (1024 * 1024) + " MB are already waiting",
            MAX_BACKLOG_MESSAGES,
            MAX_BACKLOG_BYTES);
    private volatile boolean closed;

    private volatile @Nullable Subscriber subscriber;
    private @Nullable Thread subscriberThread;
    /** The worker thread's publishing connection; only the worker opens and closes it. */
    private @Nullable Jedis publisher;

    private volatile boolean publishFailing;

    /**
     * A transport for {@code uri}, {@code redis://} or {@code rediss://} with a host, logging what the broker reports
     * through {@code redaction}.
     *
     * @throws IllegalArgumentException when {@code uri} is not such a URI
     */
    RedisTransport(String uri, Redaction redaction, Timings timings) {
        this.uri = parse(uri);
        this.redaction = redaction;
        this.timings = timings;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            closePublisher();
                        }
                    },
                    "CultAC network Redis publisher");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CultAC network Redis watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    static URI parse(String uri) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("network.uri is not a URI");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("redis") && !scheme.equals("rediss") || parsed.getHost() == null)
            throw new IllegalArgumentException("network.uri must be redis://host:port or rediss://host:port");
        if (parsed.getPort() != -1) return parsed;
        String userInfo = parsed.getRawUserInfo() == null ? "" : parsed.getRawUserInfo() + "@";
        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
        try {
            return new URI(
                    parsed.getScheme() + "://" + userInfo + parsed.getHost() + ":" + DEFAULT_PORT + path + query);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("network.uri is not a URI");
        }
    }

    @Override
    public String name() {
        return "Redis";
    }

    @Override
    public synchronized void start(List<String> channels, Receiver receiver) {
        if (subscriberThread != null) return;
        String[] subscribed = channels.toArray(String[]::new);
        Thread thread = new Thread(() -> subscribe(subscribed, receiver), "CultAC network Redis subscriber");
        thread.setDaemon(true);
        subscriberThread = thread;
        thread.start();
        watchdog.scheduleAtFixedRate(this::ping, timings.pingMillis(), timings.pingMillis(), TimeUnit.MILLISECONDS);
    }

    private void subscribe(String[] channels, Receiver receiver) {
        long backoff = MIN_BACKOFF_MILLIS;
        boolean warned = false;
        while (!closed) {
            Subscriber listener = new Subscriber(receiver);
            try (Jedis jedis = new Jedis(uri, timings.connectMillis(), timings.readMillis())) {
                listener.connection = jedis;
                subscriber = listener;
                if (closed) break;
                jedis.subscribe(listener, channels);
            } catch (RuntimeException failure) {
                if (closed) break;
                if (listener.confirmed) backoff = MIN_BACKOFF_MILLIS;
                if (!warned || listener.confirmed)
                    LogUtil.warn("Network transport Redis: "
                            + (listener.confirmed ? "lost the connection" : "cannot connect") + " ("
                            + redaction.redact(failure.getMessage()) + "); retrying");
                warned = true;
            } finally {
                subscriber = null;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
    }

    /** Pings the subscribed connection, and drops it when the previous ping went unanswered for too long. */
    private void ping() {
        Subscriber listener = subscriber;
        if (listener == null || !listener.isSubscribed()) return;
        long now = System.nanoTime();
        long pinged = listener.unansweredPing;
        if (pinged != 0 && now - pinged > TimeUnit.MILLISECONDS.toNanos(timings.pongTimeoutMillis())) {
            listener.disconnect();
            return;
        }
        try {
            listener.ping();
            if (pinged == 0) listener.unansweredPing = now;
        } catch (RuntimeException broken) {
            listener.disconnect();
        }
    }

    @Override
    public void publish(String channel, String text) {
        if (closed) return;
        int size = EnvelopeCodec.utf8Length(text);
        if (!backlog.admit(size)) return;
        try {
            worker.execute(() -> {
                try {
                    send(channel, text);
                } finally {
                    backlog.release(size);
                }
            });
        } catch (RejectedExecutionException stopped) {
            backlog.release(size);
        }
    }

    /** Publishes on the worker thread, reconnecting once; sends nothing once {@link #close} interrupted the worker. */
    private void send(String channel, String text) {
        for (int attempt = 0; attempt < 2 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                Jedis connection = publisher;
                if (connection == null) {
                    connection = new Jedis(uri, timings.connectMillis(), timings.readMillis());
                    publisher = connection;
                }
                if (Thread.currentThread().isInterrupted()) return;
                connection.publish(channel, text);
                publishFailing = false;
                return;
            } catch (RuntimeException failure) {
                closePublisher();
                if (attempt == 1 && !publishFailing && !Thread.currentThread().isInterrupted()) {
                    publishFailing = true;
                    LogUtil.warn(
                            "Network transport Redis: cannot publish (" + redaction.redact(failure.getMessage()) + ")");
                }
            }
        }
    }

    private void closePublisher() {
        Jedis connection = publisher;
        publisher = null;
        if (connection == null) return;
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // A broken connection has nothing left to close.
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            closed = true;
            thread = subscriberThread;
        }
        watchdog.shutdownNow();
        Subscriber listener = subscriber;
        if (listener != null) listener.disconnect();
        if (thread != null) thread.interrupt();
        worker.shutdown();
        try {
            if (worker.awaitTermination(timings.drainMillis(), TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        worker.shutdownNow();
    }

    private static final class Subscriber extends JedisPubSub {
        private final Receiver receiver;
        volatile @Nullable Jedis connection;
        volatile boolean confirmed;
        /** When the oldest ping still awaiting its pong was sent, in {@link System#nanoTime}; 0 when none is. */
        volatile long unansweredPing;

        Subscriber(Receiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            if (!confirmed) LogUtil.info("Network transport Redis: subscribed");
            confirmed = true;
        }

        @Override
        public void onMessage(String channel, String message) {
            receiver.receive(channel, message);
        }

        @Override
        public void onPong(String pattern) {
            unansweredPing = 0;
        }

        void disconnect() {
            try {
                if (isSubscribed()) unsubscribe();
            } catch (RuntimeException ignored) {
                // The connection closes below either way.
            }
            Jedis jedis = connection;
            if (jedis == null) return;
            try {
                jedis.disconnect();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
        }
    }
}
