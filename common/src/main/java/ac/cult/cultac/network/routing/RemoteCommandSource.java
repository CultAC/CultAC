package ac.cult.cultac.network.routing;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The sender of a command another instance, or another engine generation here, forwarded to this one: the name, UUID
 * and console flag its own instance reported, only the permission nodes that instance granted, and no player. Its
 * output goes back to the sender with the answer once the command completed; output after that is dropped.
 */
public final class RemoteCommandSource implements Sender {
    /**
     * The loader's runtime administration and the cloud commands that run on the sender's own instance. Findings stay
     * allowed: each instance holds those of its own players.
     */
    static final Set<String> DENIED =
            Set.of("cult.update", "cult.devswap", "cult.runtimes", "cult.cloud", "cult.cloud.label");

    private final UUID uuid;
    private final String name;
    private final boolean console;
    private final Set<String> granted;
    private final Consumer<Component> output;
    private final @Nullable Object base;
    private volatile boolean done;
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
        this.base = base;
    }

    /** Ends the command's output; what it sends after this is dropped. */
    void done() {
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
        return !done;
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
