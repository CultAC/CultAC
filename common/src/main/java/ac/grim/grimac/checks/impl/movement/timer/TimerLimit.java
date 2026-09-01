package ac.grim.grimac.checks.impl.movement.timer;

import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;

/** Limits the renewable timer balance available after extreme latency. */
public final class TimerLimit extends TimerCheck {
    private long limitAbuseOverPing;

    public TimerLimit(GrimPlayer player) {
        super(player, CheckInfo.builder()
                .name("TimerLimit")
                .stableKey("grim.timer.limit")
                .description("The player has sent too many packets after high latency")
                .setback(10)
                .build());
    }

    @Override
    public void doCheck(PacketReceiveEvent event) {
        if (timerBalanceRealTime > System.nanoTime()) {
            // TimerCheck runs first. Do not duplicate its flag when it already
            // rejected the same packet.
            if (!event.isCancelled() && flag() && shouldSetback()) {
                player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            }
            timerBalanceRealTime -= 50e6;
        }

        limitFallBehind();
    }

    @Override
    protected void limitFallBehind() {
        long playerClock = lastMovementPlayerClock;
        if (limitAbuseOverPing != -1 && System.nanoTime() - playerClock > limitAbuseOverPing) {
            playerClock = System.nanoTime() - limitAbuseOverPing;
        }
        timerBalanceRealTime = Math.max(timerBalanceRealTime, playerClock - clockDrift);
    }

    @Override
    public void reload() {
        super.reload();
        limitAbuseOverPing = getConfig().getLongElse(getConfigName() + ".ping-abuse-limit-threshold", 1000L);
        if (limitAbuseOverPing != -1) {
            limitAbuseOverPing *= 1_000_000L;
        }
    }
}
