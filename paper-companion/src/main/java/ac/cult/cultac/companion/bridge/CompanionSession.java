package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage;
import java.util.UUID;
import org.bukkit.entity.Player;

/** An authenticated bridge to the proxy over one player's connection. */
public interface CompanionSession {
    UUID playerId();

    Player player();

    boolean isReady();

    /** Safe from any thread. */
    void send(BridgeMessage message);

    /** Runs the task on the thread that receives this session's messages, in order with them. */
    void execute(Runnable task);

    /** Queues the task to run on the main thread in order with the player's game packets; false if it cannot. */
    boolean runInPacketOrder(Runnable task);
}
