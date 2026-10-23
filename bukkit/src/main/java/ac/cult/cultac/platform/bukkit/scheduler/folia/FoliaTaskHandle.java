package ac.cult.cultac.platform.bukkit.scheduler.folia;

import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class FoliaTaskHandle implements TaskHandle {

    private final @NotNull ScheduledTask task;

    /**
     * A repeating task, which Folia would run until cancelled, is cancelled when the runtime closes. A one-shot needs
     * nothing: its guard disarms it if the runtime closes first, and Folia lets go of it once it has run.
     */
    public FoliaTaskHandle(@NotNull ScheduledTask task) {
        this.task = Objects.requireNonNull(task, "task");
        if (task.isRepeatingTask()) RuntimeEnvironment.track(this, task::cancel);
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
