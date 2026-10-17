package ac.cult.cultac.companion.bridge;

import ac.cult.cultac.bridge.protocol.BridgeMessage.Ready;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** Server access the bridge core needs. */
interface BridgePlatform {
    /** Runs the task on the main thread, inline when already there. */
    void runMain(Runnable task);

    /** Main thread only. */
    @Nullable
    Player player(UUID player);

    /** The READY body for an online player, or null when the player is gone. Main thread only. */
    @Nullable
    Ready ready(UUID player, byte[] hmac, long capabilities, int listeners);
}
