package ac.cult.cultac.platform.bukkit.scheduler.bukkit;

import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import java.util.Objects;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public class BukkitTaskHandle implements TaskHandle {

    private final @NotNull BukkitTask task;

    /** A one-shot task: its guard disarms it if the runtime closes first, and Bukkit lets go of it once it has run. */
    @Contract(pure = true)
    public BukkitTaskHandle(@NotNull BukkitTask task) {
        this.task = Objects.requireNonNull(task, "task");
    }

    /** A repeating task, which Bukkit would run until cancelled: the runtime cancels it when it closes. */
    public static BukkitTaskHandle repeating(@NotNull BukkitTask task) {
        BukkitTaskHandle handle = new BukkitTaskHandle(task);
        RuntimeEnvironment.track(handle, task::cancel);
        return handle;
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public void cancel() {
        task.cancel();
    }
}
