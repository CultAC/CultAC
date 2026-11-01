package ac.cult.cultac.platform.velocity.bridge;

import io.netty.channel.EventLoop;

/** One player's backend connection as the bridge sees it. */
interface BridgeBackend {
    /** The player's client channel loop, which the backend connection shares. */
    EventLoop eventLoop();

    boolean inPlay();

    /** Writes one frame to the backend; throws {@link IllegalStateException} once it is disconnected. */
    void write(byte[] frame);

    /** Whether the backend has been forwarded the client's movement and not yet the tick end that follows it. */
    boolean midClientTick();

    /** Runs {@code task} on the loop once, right after the client's next tick end is forwarded. */
    void afterClientTick(Runnable task);

    String serverName();
}
