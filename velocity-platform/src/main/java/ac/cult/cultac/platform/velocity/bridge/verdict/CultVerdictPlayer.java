package ac.cult.cultac.platform.velocity.bridge.verdict;

import ac.cult.cultac.checks.verdict.FlagArbiter;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

/** {@link VerdictPlayer} over a proxy {@link CultPlayer} and its client channel loop. */
final class CultVerdictPlayer implements VerdictPlayer {
    private final CultPlayer player;
    private final EventLoop loop;

    private CultVerdictPlayer(CultPlayer player, EventLoop loop) {
        this.player = player;
        this.loop = loop;
    }

    static @Nullable VerdictPlayer of(@Nullable CultPlayer player) {
        if (player == null || !(player.user.getChannel() instanceof Channel channel)) return null;
        return new CultVerdictPlayer(player, channel.eventLoop());
    }

    @Override
    public void onLoop(Runnable task) {
        if (loop.inEventLoop()) {
            task.run();
            return;
        }
        try {
            loop.execute(task);
        } catch (RejectedExecutionException shutdown) {
            // The connection is gone; its session closes without this task.
        }
    }

    @Override
    public void schedule(Runnable task, long delayMillis) {
        try {
            loop.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutdown) {
            // The connection is gone; its session closes without this task.
        }
    }

    @Override
    public void install(FlagArbiter arbiter) {
        player.setFlagArbiter(arbiter);
    }

    @Override
    public void uninstall(FlagArbiter arbiter) {
        if (player.getFlagArbiter() == arbiter) player.setFlagArbiter(null);
    }

    @Override
    public void resolveLater(Runnable task) {
        try {
            player.user.executeLater(task);
        } catch (RejectedExecutionException shutdown) {
            // The player disconnected; nothing is left to apply a flag to.
        }
    }
}
