package ac.cult.cultac.platform.velocity.bridge.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.chat.ChatType;
import net.kyori.adventure.text.Component;

/**
 * A backend console as a proxy command source: every permission granted except the loader's runtime administration,
 * output relayed to the backend. CultAC treats it as the console.
 */
public final class RelayCommandSource implements CommandSource {
    /** Async commands still reply this long after their command completed. */
    static final long LINGER_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** The loader's nodes; {@code /cult runtimes <id> <command>} runs any proxy command as its sender. */
    static final Set<String> DENIED = Set.of("cult.update", "cult.devswap", "cult.runtimes");

    private final Consumer<Component> output;
    private final LongSupplier clock;
    private volatile boolean done;
    private volatile long doneAt;

    RelayCommandSource(Consumer<Component> output, LongSupplier clock) {
        this.output = output;
        this.clock = clock;
    }

    /** Starts the linger window; output after it is dropped. */
    void done() {
        doneAt = clock.getAsLong();
        done = true;
    }

    @Override
    public Tristate getPermissionValue(String permission) {
        return DENIED.contains(permission) ? Tristate.FALSE : Tristate.TRUE;
    }

    @Override
    public void sendMessage(Component message) {
        if (done && clock.getAsLong() - doneAt > LINGER_NANOS) return;
        output.accept(message);
    }

    @Override
    public void sendMessage(Component message, ChatType.Bound boundChatType) {
        sendMessage(message);
    }
}
