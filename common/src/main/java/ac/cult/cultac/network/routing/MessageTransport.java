package ac.cult.cultac.network.routing;

import java.util.List;

/**
 * A pub/sub connection to the network's broker. It delivers text on the channels it was started with and publishes
 * text to any channel; it never verifies or parses what it carries.
 */
public interface MessageTransport {

    /** {@code Redis}, {@code NATS} or {@code RedisBungee}, for logs. */
    String name();

    /**
     * Subscribes to {@code channels} and hands each message on them to {@code receiver}, on the transport's own
     * threads, reconnecting until {@link #close}. Returns without waiting for the broker.
     */
    void start(List<String> channels, Receiver receiver);

    /** Sends {@code text} on {@code channel} without blocking the caller; dropped while disconnected. */
    void publish(String channel, String text);

    /** Sends what is still queued, briefly, then disconnects. */
    void close();

    /**
     * {@code failure} and its causes as {@code Type: message; Type: message}, with the user info of every URL in them
     * masked, for a transport's log line; never its stack trace.
     */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++) {
            if (!text.isEmpty()) text.append("; ");
            text.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) text.append(": ").append(Redaction.text(cause.getMessage()));
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return text.toString();
    }

    @FunctionalInterface
    interface Receiver {
        void receive(String channel, String text);
    }
}
