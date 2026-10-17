package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.BridgeMessage.CheckInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The per-check permission nodes CultAC on Paper registers with a false default, so operators do not hold them
 * implicitly. Nodes are registered on the main thread for each check name the first time a catalog carries it.
 */
final class CheckPermissions {
    static final CheckPermissions NONE = new CheckPermissions(Runnable::run, node -> {});

    private static final String[] PREFIXES = {"cult.exempt.", "cult.nosetback.", "cult.nomodifypacket."};

    private final Executor mainThread;
    private final Consumer<String> register;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** @param register registers one node with a false default unless it already exists; called on the main thread */
    CheckPermissions(Executor mainThread, Consumer<String> register) {
        this.mainThread = mainThread;
        this.register = register;
    }

    static boolean isCheckNode(String permission) {
        for (String prefix : PREFIXES) {
            if (permission.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Any thread; registers the nodes of the check names not seen before. */
    void register(List<CheckInfo> checks) {
        List<String> fresh = null;
        for (CheckInfo check : checks) {
            String name = check.configName();
            if (name.isEmpty()) continue;
            String id = name.toLowerCase(Locale.ROOT);
            if (!seen.add(id)) continue;
            if (fresh == null) fresh = new ArrayList<>();
            fresh.add(id);
        }
        if (fresh == null) return;
        List<String> ids = fresh;
        mainThread.execute(() -> {
            for (String id : ids) {
                for (String prefix : PREFIXES) register.accept(prefix + id);
            }
        });
    }
}
