package ac.cult.cultac.network.routing;

import ac.cult.cultac.utils.anticheat.LogThrottle;
import ac.cult.cultac.utils.anticheat.LogUtil;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jetbrains.annotations.Nullable;

/**
 * NATS through jnats, which connects, reconnects and restores the subscriptions in the background. The connection is
 * known from its first event; one still connecting when this transport closes is closed at that event.
 */
final class NatsTransport implements MessageTransport {

    /** Starts connecting in the background: {@link Nats#connectAsynchronously}, or a stand-in in tests. */
    @FunctionalInterface
    interface Opener {
        void open(Options options) throws InterruptedException;
    }

    static final Opener NATS = options -> Nats.connectAsynchronously(options, true);

    private final Options options;
    private final Opener opener;
    private final Redaction redaction;
    private volatile boolean closed;
    private volatile @Nullable Connection connection;
    private @Nullable List<String> channels;
    private @Nullable Receiver receiver;
    private boolean subscribed;
    private boolean warned;
    /** The connection handed to {@link #close(Connection)} once this transport closed. */
    private @Nullable Connection closing;

    /**
     * A transport for the servers in {@code nats}, authenticating with its token, else its user and password, and its
     * credentials file, logging what the servers report through {@code redaction}.
     *
     * @throws IllegalArgumentException when the servers or the credentials file cannot be used
     */
    NatsTransport(NetworkSettings.Nats nats, Redaction redaction, Opener opener) {
        this.redaction = redaction;
        this.opener = opener;
        Options.Builder builder = new Options.Builder()
                .connectionName("CultAC")
                .connectionTimeout(Duration.ofSeconds(5))
                .reconnectWait(Duration.ofSeconds(2))
                .maxReconnects(-1)
                .noEcho()
                .connectionListener(this::connectionEvent)
                .errorListener(new Errors());
        if (!nats.token().isEmpty()) builder.token(nats.token().toCharArray());
        else if (!nats.username().isEmpty() || !nats.password().isEmpty())
            builder.userInfo(nats.username().toCharArray(), nats.password().toCharArray());
        if (!nats.credentialsFile().isEmpty()) {
            Path credentials = Path.of(nats.credentialsFile());
            if (!Files.isReadable(credentials))
                throw new IllegalArgumentException("network.nats.credentials-file " + credentials + " cannot be read");
            builder.authHandler(Nats.credentials(credentials.toString()));
        }
        List<String> servers = nats.servers();
        try {
            this.options = builder.servers(servers.toArray(String[]::new)).build();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IllegalArgumentException("network.nats.address: " + invalid.getMessage());
        }
    }

    @Override
    public String name() {
        return "NATS";
    }

    @Override
    public synchronized void start(List<String> channels, Receiver receiver) {
        if (this.channels != null) return;
        this.channels = List.copyOf(channels);
        this.receiver = receiver;
        try {
            opener.open(options);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void connectionEvent(Connection opened, ConnectionListener.Events type) {
        if (adopted(opened)) return;
        switch (type) {
            case CONNECTED, RECONNECTED -> connected(opened, type);
            case DISCONNECTED -> LogUtil.warn("Network transport NATS: disconnected; reconnecting");
            default -> {}
        }
    }

    /** Remembers {@code opened}; true when this transport is closed, closing {@code opened} on a thread of its own. */
    private boolean adopted(Connection opened) {
        connection = opened;
        if (!closed) return false;
        if (handOff(opened)) {
            Thread closer = new Thread(() -> close(opened), "CultAC network NATS close");
            closer.setDaemon(true);
            closer.start();
        }
        return true;
    }

    /** Whether {@code open} is not yet being closed, marking it so. */
    private synchronized boolean handOff(Connection open) {
        if (closing == open) return false;
        closing = open;
        return true;
    }

    private synchronized void connected(Connection opened, ConnectionListener.Events type) {
        String url = redaction.redact(opened.getConnectedUrl());
        if (subscribed) {
            LogUtil.info("Network transport NATS: reconnected to " + url);
            return;
        }
        Receiver deliver = receiver;
        try {
            Dispatcher dispatcher = opened.createDispatcher(message ->
                    deliver.receive(message.getSubject(), new String(message.getData(), StandardCharsets.UTF_8)));
            for (String channel : channels) dispatcher.subscribe(channel);
        } catch (RuntimeException failure) {
            LogUtil.error("Network transport NATS: cannot subscribe (" + redaction.redact(failure.getMessage()) + ")");
            closed = true;
            adopted(opened);
            return;
        }
        subscribed = true;
        LogUtil.info("Network transport NATS: connected to " + url);
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
        closed = true;
        Connection open = connection;
        if (open != null && handOff(open)) close(open);
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

    /** Server errors are logged; a failed connect is logged once, until a connection succeeds. */
    private final class Errors implements ErrorListener {
        @Override
        public void errorOccurred(Connection connection, String error) {
            if (adopted(connection)) return;
            LogUtil.warn("Network transport NATS: the server reported " + LogThrottle.clip(redaction.redact(error)));
        }

        @Override
        public void exceptionOccurred(Connection connection, Exception exception) {
            if (adopted(connection)) return;
            synchronized (NatsTransport.this) {
                if (subscribed || warned) return;
                warned = true;
            }
            LogUtil.warn("Network transport NATS: cannot connect (" + redaction.redact(exception.getMessage())
                    + "); retrying");
        }
    }
}
