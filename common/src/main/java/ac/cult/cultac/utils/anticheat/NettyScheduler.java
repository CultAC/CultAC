package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.lists.EvictingQueue;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Per-player scheduler bound to the player's own netty event loop. Runs the
 * recurring setback watchdog and offers delayed one-shot task scheduling on
 * the player's channel thread.
 */
public class NettyScheduler {
    private static final int WATCHDOG_PERIOD_MS = 60;

    final CultPlayer player;
    final ScheduledFuture<?> watchdogTask;

    public NettyScheduler(CultPlayer player) {
        this.player = player;
        watchdogTask = channel()
                .eventLoop()
                .scheduleAtFixedRate(this::watchdog, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private Channel channel() {
        return (Channel) player.user.getChannel();
    }

    private void watchdog() {
        long now = System.nanoTime();
        // Watchdogs can author a correction and its surrounding proofs.
        // Keep that entire operation behind earlier outbound packets.
        // A separate packet owner can still hold earlier writes whose listeners the tick must observe.
        if (player.user.getPacketExecutor() == channel().eventLoop() && idle(now)) player.user.flushAfterWrites();
        else player.user.executeAfterWrites(this::tick);
    }

    /** True when {@link #tick} run at {@code now} only reads state; a failure takes the task path, which reports it. */
    private boolean idle(long now) {
        try {
            return !knockbackLatencyExceeded(now)
                    && !player.getSetbackTeleportUtil().mustMoveDue(now)
                    && !player.clientTickEndTimeout.needsPoll(now);
        } catch (Throwable failure) {
            return false;
        }
    }

    private void tick() {
        player.clientTickEndTimeout.poll(System.nanoTime());
        watchdogKnockbackLatency(System.nanoTime());
        player.getSetbackTeleportUtil().checkIfMustMove();
    }

    public ScheduledFuture<?> runTaskInMs(Runnable runnable, int ms) {
        return player.user.getPacketExecutor().schedule(() -> player.runSafely(runnable), ms, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> runTaskInMs(PlayerTask task, int ms) {
        return player.user.getPacketExecutor().schedule(task, ms, TimeUnit.MILLISECONDS);
    }

    private void watchdogKnockbackLatency(long now) {
        if (knockbackLatencyExceeded(now)) {
            player.getSetbackTeleportUtil()
                    .executeTooHighLatencySetback(String.format("kb %dms", knockbackLatencyMs(now)));
        }
    }

    private boolean knockbackLatencyExceeded(long now) {
        // Nothing to watchdog until a knockback has actually been sent to the player.
        if (player.checkManager.getKnockbackHandler().lastSent == null) return false;
        if (player.inVehicle()) return false;
        if (player.checkManager.getSimulationProcessor().getLastMovementTime().isEmpty()) return false;

        // TODO: Configurable max ping
        int allowedLatencyMs = CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50;
        return knockbackLatencyMs(now) > allowedLatencyMs;
    }

    private int knockbackLatencyMs(long now) {
        EvictingQueue<Long> movementTimes =
                player.checkManager.getSimulationProcessor().getLastMovementTime();
        long mostRecentMovement = Collections.max(movementTimes);
        int elapsedMs = (int) ((now - mostRecentMovement) / 1e6);

        // Stalled transactions stall movement just the same; watchdog whichever gap is larger.
        return Math.max(elapsedMs, player.getTransactionPing());
    }

    public void removeScheduler() {
        watchdogTask.cancel(false);
    }

    /** Runs a task through {@link CultPlayer#runSafely}; built once to be scheduled repeatedly. */
    public static final class PlayerTask implements Runnable {
        private final CultPlayer player;
        private final Runnable task;

        public PlayerTask(CultPlayer player, Runnable task) {
            this.player = player;
            this.task = task;
        }

        @Override
        public void run() {
            player.runSafely(task);
        }
    }
}
