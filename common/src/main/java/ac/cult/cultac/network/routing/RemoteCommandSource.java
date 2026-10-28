package ac.cult.cultac.network.routing;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sender of a command another instance, or another engine generation here, forwarded to this one: the name, UUID
 * and console flag its own instance reported, only the permission nodes that instance granted, and no player. Its
 * output goes back to the sender as it is sent, until {@value #LINGER_MILLIS} ms after the command completed.
 */
public final class RemoteCommandSource implements Sender {
    static final long LINGER_MILLIS = 30_000;

    /** The loader's runtime administration and the cloud commands, which run on the sender's own instance. */
    static final Set<String> DENIED =
            Set.of("cult.update", "cult.devswap", "cult.runtimes", "cult.cloud", "cult.cloud.view", "cult.cloud.label");

    private final UUID uuid;
    private final String name;
    private final boolean console;
    private final Set<String> granted;
    private final Consumer<Component> output;
    private final LongSupplier clock;
    private final @Nullable Object base;
    private volatile boolean done;
    private volatile long doneAt;
    private volatile boolean targetLeft;

    /**
     * A sender holding the nodes of {@code granted} that this instance's commands check ({@code known}), the denied
     * ones left out. {@code base} is the platform's sender the command pipeline unwraps, or null for this one.
     */
    RemoteCommandSource(
            UUID uuid,
            String name,
            boolean console,
            Collection<String> granted,
            Set<String> known,
            Consumer<Component> output,
            LongSupplier clock,
            @Nullable Object base) {
        this.uuid = uuid;
        this.name = name;
        this.console = console;
        Set<String> held = new HashSet<>();
        for (String node : granted) {
            if (known.contains(node) && !DENIED.contains(node)) held.add(node);
        }
        this.granted = Set.copyOf(held);
        this.output = output;
        this.clock = clock;
        this.base = base;
    }

    /** Starts the linger window; output after it is dropped. */
    void done() {
        doneAt = clock.getAsLong();
        done = true;
    }

    /** Notes that the command's player was not here when it parsed, so the sender hears they left. */
    public void targetLeft() {
        targetLeft = true;
    }

    boolean left() {
        return targetLeft;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public void sendMessage(String message) {
        sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    @Override
    public void sendMessage(Component message) {
        if (isValid()) output.accept(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        return granted.contains(permission);
    }

    @Override
    public boolean hasPermission(String permission, boolean defaultIfUnset) {
        return granted.contains(permission);
    }

    @Override
    public void performCommand(String commandLine) {}

    @Override
    public boolean isConsole() {
        return console;
    }

    @Override
    public boolean isValid() {
        return !done || clock.getAsLong() - doneAt <= LINGER_MILLIS;
    }

    @Override
    public @NotNull Object getNativeSender() {
        return base != null ? base : this;
    }

    @Override
    public @Nullable PlatformPlayer getPlatformPlayer() {
        return null;
    }
}
