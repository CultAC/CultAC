package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.impl.prediction.runner.PacketModHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.TransactionVel;
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
    private final PendingPingBound pendingPingBound = new PendingPingBound();

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
                    && !pendingPingAdvances(now)
                    && pendingPingOverdueMs(now) < 0
                    && !player.getSetbackTeleportUtil().policy().mustMoveDue(now)
                    && !player.clientTickEndTimeout.needsPoll(now);
        } catch (Throwable failure) {
            return false;
        }
    }

    private void tick() {
        player.clientTickEndTimeout.poll(System.nanoTime());
        watchdogKnockbackLatency(System.nanoTime());
        watchdogPendingPing(System.nanoTime());
        player.getSetbackTeleportUtil().policy().checkIfMustMove();
    }

    public ScheduledFuture<?> runTaskInMs(Runnable runnable, int ms) {
        return player.user.getPacketExecutor().schedule(() -> player.runSafely(runnable), ms, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> runTaskInMs(PlayerTask task, int ms) {
        return player.user.getPacketExecutor().schedule(task, ms, TimeUnit.MILLISECONDS);
    }

    private void watchdogKnockbackLatency(long now) {
        if (knockbackLatencyExceeded(now)) {
            int latencyMs = knockbackLatencyMs(now);
            player.checkManager.getKnockbackHandler().forgetUnproven();
            player.checkManager.getExplosionHandler().forgetUnproven();
            player.getSetbackTeleportUtil().executeTooHighLatencySetback(String.format("kb %dms", latencyMs));
        }
    }

    private void watchdogPendingPing(long now) {
        int boundMs = CultAPI.INSTANCE.getConfigManager().getMaxPendingPingMs();
        if (boundMs <= 0) return;
        pendingPingBound.advance(now, player.oldestPendingPingSince(), player.lastTransactionReceived.get(), boundMs);
        long overdueMs = pendingPingOverdueMs(now);
        if (overdueMs < 0) return;
        // Pending state may be what holds the player in place, so an unchanged position is set back too.
        player.getSetbackTeleportUtil().executeTooHighLatencySetback("pending ping " + overdueMs + "ms", false);
        pendingPingBound.fired(now, player.lastTransactionSent.get());
    }

    private boolean pendingPingAdvances(long now) {
        int boundMs = CultAPI.INSTANCE.getConfigManager().getMaxPendingPingMs();
        if (boundMs <= 0) return false;
        return pendingPingBound.advances(
                now, player.oldestPendingPingSince(), player.lastTransactionReceived.get(), boundMs);
    }

    private long pendingPingOverdueMs(long now) {
        int boundMs = CultAPI.INSTANCE.getConfigManager().getMaxPendingPingMs();
        if (boundMs <= 0 || player.getSetbackTeleportUtil().isPendingSetback()) return -1;
        return pendingPingBound.overdueMs(now, player.oldestPendingPingSince(), lastClientTickArrivedAt(), boundMs);
    }

    private long lastClientTickArrivedAt() {
        EvictingQueue<Long> movementTimes = player.checkManager.getSimulationProcessor().getLastMovementTime();
        long arrivedAt = player.lastClientTickArrivedAt;
        return movementTimes.isEmpty() ? arrivedAt : Math.max(arrivedAt, Collections.max(movementTimes));
    }

    private boolean knockbackLatencyExceeded(long now) {
        if (player.isBedrockMovement()) {
            if (player.checkManager.getKnockbackHandler().lastSent == null || player.inVehicle()) return false;
        } else if (player.inVehicle() || player.getSetbackTeleportUtil().isPendingSetback()) return false;
        if (player.checkManager.getSimulationProcessor().getLastMovementTime().isEmpty()) return false;

        // TODO: Configurable max ping
        int allowedLatencyMs = CultAPI.INSTANCE.getConfigManager().getMaxPingKnockback() + 50;
        return knockbackLatencyMs(now) > allowedLatencyMs;
    }

    /**
     * The longer of the movement stall since a sent knockback and the age of an impulse the client has not proven; for
     * Bedrock, of the movement stall and the last answered ping's round trip.
     */
    private int knockbackLatencyMs(long now) {
        if (player.isBedrockMovement()) {
            long mostRecentMovement = Collections.max(player.checkManager.getSimulationProcessor().getLastMovementTime());
            return Math.max((int) ((now - mostRecentMovement) / 1e6), player.getTransactionPing());
        }
        int latencyMs = -1;
        if (player.checkManager.getKnockbackHandler().lastSent != null) {
            EvictingQueue<Long> movementTimes =
                    player.checkManager.getSimulationProcessor().getLastMovementTime();
            latencyMs = (int) ((now - Collections.max(movementTimes)) / 1e6);
        }
        latencyMs = Math.max(latencyMs, unprovenAgeMs(player.checkManager.getKnockbackHandler(), now));
        return Math.max(latencyMs, unprovenAgeMs(player.checkManager.getExplosionHandler(), now));
    }

    /** How long the oldest impulse of {@code handler} not yet set back for has waited on its proof; -1 for none. */
    private static int unprovenAgeMs(PacketModHandler handler, long now) {
        TransactionVel oldest = handler.oldestUnproven();
        return oldest == null ? -1 : (int) ((now - oldest.getSentAt()) / 1e6);
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
