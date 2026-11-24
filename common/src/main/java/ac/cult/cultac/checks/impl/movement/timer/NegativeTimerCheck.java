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
 *   <li>A frame of Minecraft#runTick advances its game time first, answers the pings it has received, then runs at most
 *       ten ticks and drops the whole ticks beyond them. A frame's pongs come before its ticks, so a pong after a tick
 *       end is a later frame, and a client loses ticks only in a frame of ten tick ends with no pong among them. Every
 *       tick end counts toward that run, including one a timer rejected, and a run of ten forgives the
 *       time lost.
 *   <li>Minecraft#getTickTargetMillis lengthens the client tick while the server ticks below 20 TPS, and a frame takes
 *       its tick length from the rate it knew before its own pings. The full rate is proven for a frame by a pong at
 *       that rate, a tick end, then another pong at that rate; until then every tick end and prediction restarts the
 *       balance, a passenger's included.
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

    /** The most ticks Minecraft#runTick runs in one frame. */
    private static final int TICKS_OF_A_CAPPED_FRAME = 10;

    /** How far the full tick rate is proven for the client's frames. */
    private enum RateProof {
        /** The rate is below 20 TPS, or not yet proven. */
        NONE,
        /** A pong at the full rate: its frame may still have taken the old tick length. */
        PONG,
        /** A tick end after that pong; a pong after it starts a frame at the full rate. */
        TICKED,
        /** Every tick end from here ran at the full rate. */
        PROVEN
    }

    private RateProof rateProof = RateProof.NONE;
    /** Tick ends received, rejected ones included, when the last pong arrived. */
    private long tickEndsAtPong;

    public NegativeTimerCheck(CultPlayer cultPlayer) {
        super(cultPlayer, INFO);
        restart();
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!usesClientTickEndBoundary() || !fullRateProven() || predictionComplete.isExempt()) {
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
        // The watchdog's tick end closes no client tick.
        if (event.isSynthetic()) return;
        recordTimerEvent(event, false, shouldCountClientTickEndForTimer());
        if (rateProof == RateProof.PONG) rateProof = RateProof.TICKED;
        if (!fullRateProven() || cappedFrameSincePong()) restart();
        else capBalance();
    }

    @CultPacketHandler
    public void onPong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
        if (!event.isAcceptedTransactionResponse()) return;
        // Ticks after the last pong that this handler did not see may have closed a capped frame.
        if (cappedFrameSincePong()) restart();
        recordTimerEvent(event, true, false);
        tickEndsAtPong = player.receivedClientTickEnds;
        if (!clientTicksAtFullRate()) rateProof = RateProof.NONE;
        else if (rateProof == RateProof.NONE) rateProof = RateProof.PONG;
        else if (rateProof == RateProof.TICKED) rateProof = RateProof.PROVEN;
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

    private boolean cappedFrameSincePong() {
        return player.receivedClientTickEnds - tickEndsAtPong >= TICKS_OF_A_CAPPED_FRAME;
    }

    /** Whether the tick being judged ran at the full rate; a rate below it ends the proof at once. */
    private boolean fullRateProven() {
        if (!clientTicksAtFullRate()) rateProof = RateProof.NONE;
        return rateProof == RateProof.PROVEN;
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
