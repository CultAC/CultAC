package ac.cult.cultac.platform.velocity.bridge.command;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** Builds relay sources for tests outside this package. */
public final class RelaySources {
    private RelaySources() {}

    public static RelayCommandSource relay(Consumer<Component> output) {
        return new RelayCommandSource(output, System::nanoTime);
    }
}
