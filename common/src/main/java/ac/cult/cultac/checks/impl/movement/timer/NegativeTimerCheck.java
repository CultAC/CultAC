package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundAcceptTeleportation;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * Flags a Java client whose ticks fall behind the time its pongs prove it has lived through.
 *
 * <ul>
 *   <li>A client tick is its ServerboundClientTickEnd, which Minecraft#tick sends whether or not the tick sent a
 *       movement. Where that packet cannot arrive, a tick without movement is invisible and the check never flags.
 *   <li>Minecraft#runTick answers a frame's pings, then runs at most ten ticks and drops the rest it owed. Ticks are
 *       lost only there, so a run of tick ends that long with no pong among them forgives the time lost.
 *   <li>Minecraft#getTickTargetMillis lengthens the client tick while the server ticks below 20 TPS; the balance
 *       restarts until it is back.
 * </ul>
 */
public class NegativeTimerCheck extends AbstractTimerCheck implements PostPredictionListener {
    public static final CheckInfo INFO = CheckInfo.builder()
            .name("NegativeTimer")
            .stableKey("grim.timer.negative")
            .description("Game ran slower than normal")
            .setback(-1)
            .experimental(true)
            .build();

    private static final Verbose V = Verbose.of("behind={ulong}ms");

    /** One short of the ten ticks a frame can run: a tick end another check rejected never reaches this one. */
    private static final int TICKS_OF_A_CAPPED_FRAME = 9;

    private int ticksSincePong;

    public NegativeTimerCheck(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
        restart();
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!usesClientTickEndBoundary() || !clientTicksAtFullRate() || predictionComplete.isExempt()) {
            restart();
            return;
        }
        if (timerBalanceRealTime >= knownPlayerClockTime - clockDrift) return;

        long behindMs = (System.nanoTime() - timerBalanceRealTime) / 1_000_000L;
        flagWithSetback(V.write(verbose()).ulong(behindMs));
        timerBalanceRealTime += ONE_CLIENT_TICK;
    }

    @CultPacketHandler
    public void onAcceptTeleportation(
            PacketReceiveEvent<ServerboundAcceptTeleportation> event,
            CultPlayer player,
            ServerboundAcceptTeleportation packet) {
        // A login ends in a teleport, and the client sent no tick end while it had no level.
        if (!event.isCancelled()) restart();
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
        if (++ticksSincePong >= TICKS_OF_A_CAPPED_FRAME) restart();
        else capBalance();
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        if (!event.isAcceptedTransactionResponse()) return;
        recordTimerEvent(event, true, false);
        ticksSincePong = 0;
        capBalance();
    }

    @CultPacketHandler("serverbound.container_slot_state_changed")
    public void onContainerSlotStateChanged(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        recordTimerEvent(event, true, false);
        capBalance();
    }

    /** The balance of a client that has lost no time. */
    private void restart() {
        timerBalanceRealTime = System.nanoTime() + clockDrift;
    }

    /** Ticks sent early bank nothing against a later slowdown. */
    private void capBalance() {
        timerBalanceRealTime = Math.min(timerBalanceRealTime, System.nanoTime() + clockDrift);
    }

    private boolean clientTicksAtFullRate() {
        return player.packetStateData.serverTickRate >= 20.0F;
    }

    @Override
    public void doCheck(final PacketReceiveEvent event) {
        // The inherited check is Timer's, for a client ahead of real time. This one judges after the prediction.
    }

    @Override
    public void reload() {
        super.reload();
        this.clockDrift = (long) (getConfig().getDoubleElse(getConfigName() + ".drift", 1200.0) * 1e6);
    }
}
