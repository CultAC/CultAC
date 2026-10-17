package ac.cult.cultac.platform.velocity.bridge;

import io.netty.channel.EventLoop;

/** One player's backend connection as the bridge sees it. */
interface BridgeBackend {
    /** The player's client channel loop, which the backend connection shares. */
    EventLoop eventLoop();

    boolean inPlay();

    /** Writes one frame to the backend; throws {@link IllegalStateException} once it is disconnected. */
    void write(byte[] frame);

    String serverName();
}
