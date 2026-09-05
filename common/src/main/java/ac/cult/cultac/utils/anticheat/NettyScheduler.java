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
        watchdogTask = channel().eventLoop().scheduleAtFixedRate(
                this::tick, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private Channel channel() {
        return (Channel) player.user.getChannel();
    }

    private void tick() {
        watchdogKnockbackLatency();
        player.getSetbackTeleportUtil().checkIfMustMove();
    }

    public ScheduledFuture<?> runTaskInMs(Runnable runnable, int ms) {
        return channel().eventLoop().schedule(runnable, ms, TimeUnit.MILLISECONDS);
    }

    private void watchdogKnockbackLatency() {
        // Nothing to watchdog until a knockback has actually been sent to the player.
        if (player.checkManager.getKnockbackHandler().lastSent == null) return;
        if (player.inVehicle()) return;

        EvictingQueue<Long> movementTimes = player.checkManager.getSimulationProcessor().getLastMovementTime();
        if (movementTimes.isEmpty()) return;

        long mostRecentMovement = Collections.max(movementTimes);
        int elapsedMs = (int) ((System.nanoTime() - mostRecentMovement) / 1e6);

        // Stalled transactions stall movement just the same; watchdog whichever gap is larger.
        elapsedMs = Math.max(elapsedMs, player.getTransactionPing());

        // TODO: Configurable max ping
        int allowedLatencyMs = CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50;
        if (elapsedMs > allowedLatencyMs) {
            player.getSetbackTeleportUtil().executeTooHighLatencySetback(String.format("kb %dms", elapsedMs));
        }
    }

    public void removeScheduler() {
        watchdogTask.cancel(false);
    }
}
