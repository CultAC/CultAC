package ac.cult.cultac.platform.api;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.AbstractCheck;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface PlatformServer {

    String getPlatformImplementationString();

    void dispatchCommand(Sender sender, String command);

    /**
     * Dispatches a punishment command {@code check} triggered for {@code subject}. A proxy may run it on the subject's
     * backend console, where backend CommandExecuteEvent listeners can still cancel it.
     */
    default void dispatchCommand(
            Sender sender,
            String command,
            @Nullable PlatformPlayer subject,
            AbstractCheck check,
            double violations,
            String verbose) {
        dispatchCommand(sender, command);
    }

    Sender getConsoleSender();

    void registerOutgoingPluginChannel(String name);

    void forwardAlert(String message);

    boolean isProxyForwardingEnabled();

    /**
     * Places a real server block as an explicit validation stimulus, then reports through the
     * callback. Only platforms with server authority support this.
     */
    void applyValidationBlock(
            ac.cult.cultac.platform.api.player.PlatformPlayer player,
            int x,
            int y,
            int z,
            String state,
            Runnable applied);

    double getTPS();

    /** The tick rate of the server {@code player} is on, falling back to {@link #getTPS()}. */
    default double getTPS(@Nullable UUID player) {
        return getTPS();
    }
}
