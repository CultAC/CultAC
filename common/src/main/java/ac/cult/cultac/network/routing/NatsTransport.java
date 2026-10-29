package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;

/**
 * NATS through jnats. The first connection is retried on its own thread with a backoff capped at
 * {@value #MAX_BACKOFF_MILLIS} ms; after that jnats reconnects by itself and restores the subscriptions. A token or a
 * {@code .creds} file authenticates; {@code tls://} servers connect over TLS. A connection that fails before it is
 * subscribed is closed before the next try.
 */
final class NatsTransport implements MessageTransport {
    static final long MIN_BACKOFF_MILLIS = 1_000;
    static final long MAX_BACKOFF_MILLIS = 30_000;

    /** Opens a connection: {@link Nats#connect(Options)}, or a stand-in in tests. */
    @FunctionalInterface
    interface Opener {
        Connection open(Options options) throws IOException, InterruptedException;
    }

    private final Options options;
    private final Opener opener;
    private final Redaction redaction;
    private volatile boolean closed;
    private volatile @Nullable Connection connection;
    private @Nullable Thread connector;

    private NatsTransport(Options.Builder options, Opener opener, Redaction redaction) {
        this.redaction = redaction;
        this.options = options.connectionListener(this::connectionEvent)
                .errorListener(new Errors())
                .build();
        this.opener = opener;
    }

    /**
     * A transport for {@code nats.servers}, authenticated by {@code nats.token} or {@code nats.credentials-file},
     * logging what the servers report through {@code redaction}.
     */
    static NatsTransport create(NetworkSettings settings, Redaction redaction) {
        return create(settings, redaction, Nats::connect);
    }

    static NatsTransport create(NetworkSettings settings, Opener opener) {
        return create(settings, Redaction.of(settings), opener);
    }

    static NatsTransport create(NetworkSettings settings, Redaction redaction, Opener opener) {
        if (settings.natsServers().isEmpty())
            throw new IllegalArgumentException("network.nats.servers lists no server");
        Options.Builder options = new Options.Builder()
                .servers(settings.natsServers().toArray(String[]::new))
                .connectionName("CultAC")
                .connectionTimeout(Duration.ofSeconds(5))
                .reconnectWait(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .noEcho();
        if (!settings.natsToken().isEmpty()) options.token(settings.natsToken().toCharArray());
        if (!settings.natsCredentialsFile().isEmpty()) {
            Path credentials = Path.of(settings.natsCredentialsFile());
            if (!Files.isReadable(credentials))
                throw new IllegalArgumentException("network.nats.credentials-file " + credentials + " cannot be read");
            options.authHandler(Nats.credentials(credentials.toString()));
        }
        try {
            return new NatsTransport(options, opener, redaction);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IllegalArgumentException("network.nats: " + invalid.getMessage());
        }
    }

    @Override
    public String name() {
        return "NATS";
    }

    @Override
    public synchronized void start(List<String> channels, Receiver receiver) {
        if (connector != null) return;
        Thread thread = new Thread(() -> connect(channels, receiver), "CultAC network NATS connect");
        thread.setDaemon(true);
        connector = thread;
        thread.start();
    }

    private void connect(List<String> channels, Receiver receiver) {
        long backoff = MIN_BACKOFF_MILLIS;
        boolean warned = false;
        while (!closed) {
            try {
                Connection opened = subscribed(channels, receiver);
                connection = opened;
                if (closed) close(opened);
                else LogUtil.info("Network transport NATS: connected to " + redaction.redact(opened.getConnectedUrl()));
                return;
            } catch (IOException | RuntimeException failure) {
                if (!warned)
                    LogUtil.warn("Network transport NATS: cannot connect (" + redaction.redact(failure.getMessage())
                            + "); retrying");
                warned = true;
            } catch (InterruptedException interrupted) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
    }

    /** A connection subscribed to {@code channels}; one that fails on the way is closed before the failure is thrown. */
    private Connection subscribed(List<String> channels, Receiver receiver) throws IOException, InterruptedException {
        Connection opened = opener.open(options);
        try {
            Dispatcher dispatcher = opened.createDispatcher(message ->
                    receiver.receive(message.getSubject(), new String(message.getData(), StandardCharsets.UTF_8)));
            for (String channel : channels) dispatcher.subscribe(channel);
            return opened;
        } catch (RuntimeException failure) {
            close(opened);
            throw failure;
        }
    }

    @Override
    public void publish(String channel, String text) {
        Connection open = connection;
        if (open == null) return;
        try {
            open.publish(channel, text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException closedOrFull) {
            // Closed, or reconnecting with a full buffer: the message is lost, as the contract allows.
        }
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            closed = true;
            thread = connector;
        }
        if (thread != null) thread.interrupt();
        Connection open = connection;
        if (open != null) close(open);
    }

    private static void close(Connection open) {
        try {
            open.flush(Duration.ofSeconds(1));
        } catch (TimeoutException | InterruptedException | IllegalStateException unflushed) {
            // Whatever did not leave in time is lost.
        }
        try {
            open.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void connectionEvent(Connection opened, ConnectionListener.Events type) {
        if (closed) return;
        switch (type) {
            case DISCONNECTED -> LogUtil.warn("Network transport NATS: disconnected; reconnecting");
            case RECONNECTED ->
                LogUtil.info("Network transport NATS: reconnected to " + redaction.redact(opened.getConnectedUrl()));
            default -> {}
        }
    }

    /** Connection failures surface as connection events; only the server's own errors are logged here. */
    private final class Errors implements ErrorListener {
        @Override
        public void errorOccurred(Connection connection, String error) {
            LogUtil.warn("Network transport NATS: the server reported " + LogThrottle.clip(redaction.redact(error)));
        }

        @Override
        public void exceptionOccurred(Connection connection, Exception exception) {}
    }
}
