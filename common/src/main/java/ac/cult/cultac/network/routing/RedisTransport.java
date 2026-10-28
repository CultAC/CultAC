package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.jetbrains.annotations.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.SSLSocketWrapper;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.util.JedisURIHelper;

/**
 * Redis pub/sub through Jedis. One connection subscribes on its own thread and reconnects with a backoff capped at
 * {@value #MAX_BACKOFF_MILLIS} ms. A subscribed connection reads without a timeout, so a watchdog thread of its own
 * pings it every {@value #PING_MILLIS} ms and drops it when no pong came back within {@value #PONG_TIMEOUT_MILLIS} ms.
 * A second connection publishes from one worker thread, at most {@value #MAX_BACKLOG_MESSAGES} messages of
 * {@value #MAX_BACKLOG_BYTES} bytes in all waiting for it; the rest are dropped. It tries the host's addresses within
 * {@value #CONNECT_TIMEOUT_MILLIS} ms in all. Closing waits {@value #DRAIN_MILLIS} ms for the backlog, then closes the
 * publishing socket under a blocked send or a connection still connecting, shaking hands or authenticating, and the
 * worker sends nothing more. A {@code rediss://} URI connects over TLS.
 */
final class RedisTransport implements MessageTransport {
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    static final int READ_TIMEOUT_MILLIS = 10_000;
    static final long PING_MILLIS = 10_000;
    static final long PONG_TIMEOUT_MILLIS = 30_000;
    static final int DEFAULT_PORT = 6379;
    static final long MIN_BACKOFF_MILLIS = 1_000;
    static final long MAX_BACKOFF_MILLIS = 30_000;
    static final long DRAIN_MILLIS = 2_000;
    static final int MAX_BACKLOG_MESSAGES = 4096;
    static final long MAX_BACKLOG_BYTES = 16L * 1024 * 1024;

    private final URI uri;
    private final Redaction redaction;
    private final JedisClientConfig publisherConfig;
    private final long pingMillis;
    private final long pongTimeoutMillis;
    /** Publishes in order on one thread, which closes its connection when it ends. */
    private final ExecutorService worker;

    private final ScheduledExecutorService watchdog;
    private final Backlog backlog = new Backlog(
            "Network transport Redis: dropped a message to publish; " + MAX_BACKLOG_MESSAGES + " messages or "
                    + MAX_BACKLOG_BYTES / (1024 * 1024) + " MB are already waiting",
            MAX_BACKLOG_MESSAGES,
            MAX_BACKLOG_BYTES);
    private volatile boolean closed;
    /** Set once {@link #close} stops waiting for the backlog; the worker tries no further send once it sees it. */
    private volatile boolean abandoned;

    private volatile @Nullable Subscriber subscriber;
    private @Nullable Thread subscriberThread;
    /** The worker thread's publishing connection; only the worker opens and closes it. */
    private volatile @Nullable Jedis publisher;
    /** The publishing connection's TCP socket, from before it connects until the connection closes. */
    private volatile @Nullable Socket publisherSocket;

    private volatile boolean publishFailing;

    private RedisTransport(URI uri, Redaction redaction, long pingMillis, long pongTimeoutMillis) {
        this.uri = uri;
        this.redaction = redaction;
        this.publisherConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
                .socketTimeoutMillis(READ_TIMEOUT_MILLIS)
                .user(JedisURIHelper.getUser(uri))
                .password(JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri))
                .protocol(JedisURIHelper.getRedisProtocol(uri))
                .ssl(JedisURIHelper.isRedisSSLScheme(uri))
                .build();
        this.pingMillis = pingMillis;
        this.pongTimeoutMillis = pongTimeoutMillis;
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

    /**
     * A transport for {@code uri}, {@code redis://} or {@code rediss://} with a host, logging what the broker reports
     * through {@code redaction}.
     */
    static RedisTransport create(String uri, Redaction redaction) {
        return new RedisTransport(parse(uri), redaction, PING_MILLIS, PONG_TIMEOUT_MILLIS);
    }

    static RedisTransport create(String uri) {
        return create(uri, PING_MILLIS, PONG_TIMEOUT_MILLIS);
    }

    static RedisTransport create(String uri, long pingMillis, long pongTimeoutMillis) {
        return new RedisTransport(parse(uri), Redaction.of(List.of(uri), List.of()), pingMillis, pongTimeoutMillis);
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
        watchdog.scheduleAtFixedRate(this::ping, pingMillis, pingMillis, TimeUnit.MILLISECONDS);
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
        if (pinged != 0 && now - pinged > TimeUnit.MILLISECONDS.toNanos(pongTimeoutMillis)) {
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

    private void send(String channel, String text) {
        for (int attempt = 0; attempt < 2 && !abandoned; attempt++) {
            try {
                Jedis connection = publisher;
                if (connection == null) connection = openPublisher();
                if (abandoned) {
                    closePublisher();
                    return;
                }
                connection.publish(channel, text);
                publishFailing = false;
                return;
            } catch (RuntimeException failure) {
                closePublisher();
                if (attempt == 1 && !publishFailing) {
                    publishFailing = true;
                    LogUtil.warn(
                            "Network transport Redis: cannot publish (" + redaction.redact(failure.getMessage()) + ")");
                }
            }
        }
    }

    private Jedis openPublisher() {
        try {
            Jedis connection = new Jedis(this::openSocket, publisherConfig);
            publisher = connection;
            return connection;
        } catch (RuntimeException failure) {
            closePublisherSocket();
            throw failure;
        }
    }

    /**
     * The publishing connection's socket, connected to one of the host's addresses before the connect timeout runs out
     * and wrapped in TLS as Jedis wraps it for {@code rediss://}. Each attempt's socket is {@link #publisherSocket}
     * before it connects, so {@link #close} can abort it; one created after {@link #close} stopped waiting is closed at
     * once.
     */
    private Socket openSocket() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MILLIS);
        List<InetAddress> addresses;
        try {
            addresses = Arrays.asList(InetAddress.getAllByName(uri.getHost()));
        } catch (UnknownHostException unknown) {
            throw new JedisConnectionException("Unknown host " + uri.getHost(), unknown);
        }
        if (addresses.size() > 1) Collections.shuffle(addresses);
        JedisConnectionException failed =
                new JedisConnectionException("Failed to connect to " + uri.getHost() + ":" + uri.getPort() + ".");
        for (InetAddress address : addresses) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) break;
            Socket socket = new Socket();
            publisherSocket = socket;
            if (abandoned) {
                closeQuietly(socket);
                throw new JedisConnectionException("the transport is closed");
            }
            try {
                socket.setReuseAddress(true);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.setSoLinger(true, 0);
                socket.connect(new InetSocketAddress(address, uri.getPort()), (int) remaining);
                socket.setSoTimeout(READ_TIMEOUT_MILLIS);
                return publisherConfig.isSsl() ? tls(socket) : socket;
            } catch (IOException | RuntimeException attempt) {
                closeQuietly(socket);
                failed.addSuppressed(attempt);
            }
        }
        throw failed;
    }

    /** {@code socket} under TLS that checks the host name. */
    private Socket tls(Socket socket) throws IOException {
        SSLSocket secured = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(socket, uri.getHost(), uri.getPort(), true);
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        secured.setSSLParameters(parameters);
        return new SSLSocketWrapper(secured, socket);
    }

    private void closePublisher() {
        Jedis connection = publisher;
        publisher = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (RuntimeException ignored) {
                // A broken connection has nothing left to close.
            }
        }
        closePublisherSocket();
    }

    private void closePublisherSocket() {
        Socket socket = publisherSocket;
        publisherSocket = null;
        if (socket != null) closeQuietly(socket);
    }

    /**
     * Closes the publishing connection's TCP socket without flushing, so a connect, a TLS handshake, an authentication
     * or a send blocked on it fails at once.
     */
    private void dropPublisher() {
        Socket socket = publisherSocket;
        if (socket != null) closeQuietly(socket);
        Jedis connection = publisher;
        if (connection == null) return;
        try {
            connection.getConnection().forceDisconnect();
        } catch (IOException | RuntimeException ignored) {
            // The socket is closed or was never opened.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
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
            if (worker.awaitTermination(DRAIN_MILLIS, TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        abandoned = true;
        worker.shutdownNow();
        dropPublisher();
    }

    /** Whether the publishing worker, told to stop by {@link #close}, ends within {@code millis} ms. */
    boolean awaitStopped(long millis) throws InterruptedException {
        return worker.awaitTermination(millis, TimeUnit.MILLISECONDS);
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
