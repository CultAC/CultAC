package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.Hello;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** One player connection's end of the bridge; all inbound traffic for it arrives on one thread. */
abstract class BridgeEndpoint {
    volatile @Nullable BridgeSession session;
    volatile boolean authWarned;
    /** The latest HELLO that arrived before the player joined; handled once the player attaches. */
    volatile @Nullable Hello pendingHello;

    /** The player this connection carries, or null before the player has joined. */
    abstract @Nullable UUID playerId();

    /** Safe from any thread. */
    abstract void send(BridgeMessage message);

    /** Runs the task on the thread that receives this connection's inbound traffic. */
    abstract void execute(Runnable task);

    /** Queues the task to run on the main thread in order with this connection's game packets; false if it cannot. */
    boolean runInPacketOrder(Runnable task) {
        return false;
    }
}
