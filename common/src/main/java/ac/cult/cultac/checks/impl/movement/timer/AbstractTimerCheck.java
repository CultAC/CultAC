package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;

// @CheckData(name = "Timer", configName = "TimerA", setback = 10)
public abstract class AbstractTimerCheck extends Check implements CheckListener {
    private static final long ONE_CLIENT_TICK = 50_000_000L;
    private final boolean serverSupportsClientTickEnd;

    long timerBalanceRealTime = 0;

    // Default value is real time minus max keep-alive time
    long knownPlayerClockTime = (long) (System.nanoTime() - 6e10);
    long lastMovementPlayerClock = (long) (System.nanoTime() - 6e10);

    // How long should the player be able to fall back behind their ping?
    // Default: 120 milliseconds
    long clockDrift = (long) 120e6;

    boolean hasGottenMovementAfterTransaction = false;
    private int movePacketsSinceLastClientTickEnd = 0;

    // Proof for this timer check
    // https://i.imgur.com/Hk2Wb6c.png
    //
    // The largest gap will always be the transaction ping (server -> client -> server)
    // Proof lies that client -> server ping will always be lower
    //
    // The largest gap is the floor for movements
    // If the smaller gap surpasses the larger gap, the player is cheating
    //
    // This usually flags 1.01 on low ping extremely quickly
    // Higher ping/low fps scales proportionately, and will flag less quickly but will still always flag 1.01
    // Players standing still will reset this amount of time
    //
    // This is better than traditional timer checks because ping fluctuations will never affect this check
    // As we are tying this check to the player's ping, rather than real time.
    //
    // Tested 10/20/30 fps and f3 + t spamming for lag spikes at 0 ping localhost/200 ping clumsy, no falses
    // Also didn't false when going from 0 -> 2000 ms ping, and 2000 ms -> 0 ms ping
    // it's a very nice check, in my opinion.  I guess I will find out if netty lag can false it

    // You might notice that we deviate a bit from this to handle lag
    // We take the FIRST transaction after each movement, to avoid issues with this packet order at low FPS:
    // TRANSACTION TRANSACTION TRANSACTION MOVEMENT MOVEMENT MOVEMENT
    // TRANSACTION TRANSACTION TRANSACTION MOVEMENT MOVEMENT MOVEMENT
    //
    // We then take the last transaction before this to increase stability with these lag spikes and
    // to guarantee that we are at least 50 ms back before adding the time
    public AbstractTimerCheck(CultPlayer cultPlayer, CheckInfo info) {
        super(cultPlayer, info);
        serverSupportsClientTickEnd = CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .supports(ServerboundPackets.CLIENT_TICK_END);
    }

    public void resetTimerWindow() {
        long now = System.nanoTime() - ONE_CLIENT_TICK;
        timerBalanceRealTime = now;
        knownPlayerClockTime = now;
        lastMovementPlayerClock = now;
        hasGottenMovementAfterTransaction = false;
        movePacketsSinceLastClientTickEnd = 0;
    }

    protected void recordModernMovePlayerPacket(PacketReceiveEvent event, boolean countClientTickMovement) {
        if (!usesClientTickEndBoundary()) return;
        if (!countClientTickMovement) return;

        // MCP-Reborn 1.21.2 LocalPlayer#tick calls sendPosition() once per
        // client tick and Minecraft#tick sends ServerboundClientTickEndPacket
        // once at tick end. More than one move packet before tick-end is not
        // produced by the vanilla client and is therefore provably invalid.
        // Set back immediately: the VL window was a renewable speed budget
        // (cadence-split bypass) — each extra packet is a full free physics
        // tick. Reject this packet here: ordinary setback blocking runs after
        // Java prediction, but a timer rejection must never advance prediction.
        if (++movePacketsSinceLastClientTickEnd > 1 && flag()) {
            player.getSetbackTeleportUtil().executeNonSimulatingSetback();
            if (player.shouldEnforceMovementSetbacks()
                    && player.getSetbackTeleportUtil().shouldBlockMovement()) {
                event.setCancelled(true);
            }
        }
    }

    protected void recordModernClientTickEndPacket() {
        if (!usesClientTickEndBoundary()) return;
        movePacketsSinceLastClientTickEnd = 0;
    }

    protected void recordTimerEvent(
            final PacketReceiveEvent event, boolean transactionResponse, boolean countClientTick) {
        if (prepareTimerEvent(transactionResponse, countClientTick)) {
            doCheck(event);
        }
    }

    protected final boolean recordTimerEventForPacketDecision(boolean transactionResponse, boolean countClientTick) {
        return prepareTimerEvent(transactionResponse, countClientTick) && doCheckForPacketDecision(null);
    }

    private boolean prepareTimerEvent(boolean transactionResponse, boolean countClientTick) {
        if (hasGottenMovementAfterTransaction && transactionResponse) {
            knownPlayerClockTime = lastMovementPlayerClock;
            lastMovementPlayerClock = player.getPlayerClockAtLeast();
            hasGottenMovementAfterTransaction = false;
        }

        if (!countClientTick) return false;

        hasGottenMovementAfterTransaction = true;
        timerBalanceRealTime += 50e6;

        return true;
    }

    public void doCheck(final PacketReceiveEvent event) {
        doCheckForPacketDecision(event);
    }

    private boolean doCheckForPacketDecision(final PacketReceiveEvent event) {
        boolean rejected = false;
        if (timerBalanceRealTime > System.nanoTime()) {
            if (flag()) {
                // Cancel the packet
                if (shouldModifyPackets()) {
                    if (event != null) {
                        event.setCancelled(true);
                    }
                    player.onPacketCancel();
                    rejected = true;
                }
                this.setbackIfAboveSetbackVLNonSimulating();
            }

            // Reset the violation by 1 movement
            timerBalanceRealTime -= 50e6;
        }

        this.timerBalanceRealTime = Math.max(this.timerBalanceRealTime, this.knownPlayerClockTime - this.clockDrift);
        return rejected;
    }

    protected boolean shouldCountMovePlayerForTimer() {
        if (player.isBedrockMovement()) {
            return false;
        }
        // TODO: We need a second check for too many move packets!
        return !usesClientTickEndBoundary() && !player.packetStateData.lastPacketWasTeleport;
    }

    protected boolean shouldCountClientTickEndForTimer() {
        if (player.isBedrockMovement()) {
            return false;
        }
        // Minecraft#tick sends tick-end after the tick's movement packets.
        return usesClientTickEndBoundary();
    }

    protected final boolean usesClientTickEndBoundary() {
        // ViaVersion cannot carry the 1.21.2 tick-end packet through an older
        // backend protocol. In that case the backend receives the traditional
        // one-movement-per-tick clock and must use that observable boundary.
        return serverSupportsClientTickEnd && !player.isBedrockMovement();
    }

    @Override
    public void reload() {
        super.reload();
        this.clockDrift = (long) (getConfig().getDoubleElse(getConfigName() + ".drift", 120.0) * 1e6);
    }
}
