package ac.cult.cultac.utils.data.packetentity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Discrete projectile-ray and attachment positions retain their own velocity and interpolation path. */
final class HookPhysicsPaths {
    private List<PacketEntityHook.State> others = List.of();
    private List<PacketEntityHook.State> added;
    private boolean running;

    boolean run(PacketEntityHook hook, Runnable operation) {
        if (running || others.isEmpty()) return false;
        var paths = new ArrayList<PacketEntityHook.State>(others.size() + 1);
        paths.add(hook.captureState());
        paths.addAll(others);
        var next = new ArrayList<PacketEntityHook.State>();
        running = true;
        try {
            for (var path : paths) {
                hook.restoreState(path);
                added = new ArrayList<>();
                operation.run();
                append(next, hook.captureState());
                for (var branch : added) append(next, branch);
            }
        } finally {
            running = false;
            added = null;
            if (!next.isEmpty()) hook.restoreState(next.getFirst());
        }
        others = List.copyOf(next.subList(1, next.size()));
        return true;
    }

    void branch(PacketEntityHook.State state) {
        if (running) {
            added.add(state);
        } else {
            var paths = new ArrayList<>(others);
            append(paths, state);
            others = List.copyOf(paths);
        }
    }

    <T> List<T> values(PacketEntityHook hook, Supplier<List<T>> read) {
        if (running || others.isEmpty()) return read.get();
        var primary = hook.captureState();
        var result = new ArrayList<>(read.get());
        running = true;
        try {
            for (var path : others) {
                hook.restoreState(path);
                for (var position : read.get()) if (!result.contains(position)) result.add(position);
            }
        } finally {
            hook.restoreState(primary);
            running = false;
        }
        return result;
    }

    private static void append(List<PacketEntityHook.State> paths, PacketEntityHook.State state) {
        for (var existing : paths) if (existing.sameSettledState(state)) return;
        paths.add(state);
    }
}
