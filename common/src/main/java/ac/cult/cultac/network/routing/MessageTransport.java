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

    @FunctionalInterface
    interface Receiver {
        void receive(String channel, String text);
    }
}
