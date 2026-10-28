package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis pub/sub through Jedis. One connection subscribes on its own thread and reconnects with a backoff capped at
 * {@value #MAX_BACKOFF_MILLIS} ms. A subscribed connection reads without a timeout, so it is pinged every
 * {@value #PING_MILLIS} ms and dropped when no pong came back within {@value #PONG_TIMEOUT_MILLIS} ms. A second
 * connection publishes from one worker thread. A {@code rediss://} URI connects over TLS.
 */
final class RedisTransport implements MessageTransport {
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int READ_TIMEOUT_MILLIS = 10_000;
    static final long PING_MILLIS = 10_000;
    static final long PONG_TIMEOUT_MILLIS = 30_000;
    static final int DEFAULT_PORT = 6379;
    static final long MIN_BACKOFF_MILLIS = 1_000;
    static final long MAX_BACKOFF_MILLIS = 30_000;

    private final URI uri;
    private final ScheduledExecutorService worker;
    private volatile boolean closed;
    private volatile @Nullable Subscriber subscriber;
    private @Nullable Thread subscriberThread;
    /** The worker thread's publishing connection. */
    private @Nullable Jedis publisher;

    private volatile boolean publishFailing;

    private RedisTransport(URI uri) {
        this.uri = uri;
        this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CultAC network Redis publisher");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** A transport for {@code uri}, {@code redis://} or {@code rediss://} with a host. */
    static RedisTransport create(String uri) {
        return new RedisTransport(parse(uri));
    }

    static URI parse(String uri) {
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("network.redis.uri is not a URI");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("redis") && !scheme.equals("rediss") || parsed.getHost() == null)
            throw new IllegalArgumentException("network.redis.uri must be redis://host:port or rediss://host:port");
        if (parsed.getPort() != -1) return parsed;
        String userInfo = parsed.getRawUserInfo() == null ? "" : parsed.getRawUserInfo() + "@";
        String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
        String query = parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery();
        try {
            return new URI(
                    parsed.getScheme() + "://" + userInfo + parsed.getHost() + ":" + DEFAULT_PORT + path + query);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("network.redis.uri is not a URI");
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
        worker.scheduleAtFixedRate(this::ping, PING_MILLIS, PING_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void subscribe(String[] channels, Receiver receiver) {
        long backoff = MIN_BACKOFF_MILLIS;
        boolean warned = false;
        while (!closed) {
            Subscriber listener = new Subscriber(receiver);
            try (Jedis jedis = new Jedis(uri, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)) {
                listener.connection = jedis;
                subscriber = listener;
                if (closed) break;
                jedis.subscribe(listener, channels);
            } catch (RuntimeException failure) {
                if (closed) break;
                if (listener.confirmed) backoff = MIN_BACKOFF_MILLIS;
                if (!warned || listener.confirmed)
                    LogUtil.warn("Network transport Redis: "
                            + (listener.confirmed ? "lost the connection" : "cannot" + " connect") + " ("
                            + failure.getMessage() + "); retrying");
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
        if (pinged != 0 && now - pinged > TimeUnit.MILLISECONDS.toNanos(PONG_TIMEOUT_MILLIS)) {
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
        try {
            worker.execute(() -> send(channel, text));
        } catch (RejectedExecutionException stopped) {
            // A closed transport sends nothing.
        }
    }

    private void send(String channel, String text) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (publisher == null) publisher = new Jedis(uri, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
                publisher.publish(channel, text);
                publishFailing = false;
                return;
            } catch (RuntimeException failure) {
                closePublisher();
                if (attempt == 1 && !publishFailing) {
                    publishFailing = true;
                    LogUtil.warn("Network transport Redis: cannot publish (" + failure.getMessage() + ")");
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
        Subscriber listener = subscriber;
        if (listener != null) listener.disconnect();
        if (thread != null) thread.interrupt();
        try {
            worker.execute(this::closePublisher);
        } catch (RejectedExecutionException alreadyClosed) {
            return;
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
