package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMoveVehicle;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "TickTimer",
        stableKey = "cult.timer.tick",
        description = "Did not send client tick end packet",
        setback = 1)
public final class TickTimer extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("type=[end|flying], packets={uint}");

    private final boolean serverSupportsClientTickEnd;
    private boolean receivedTickEnd = true;
    private int flyingPackets;
    private int vehiclePackets;

    public TickTimer(CultPlayer player) {
        super(player);
        serverSupportsClientTickEnd = CultAPI.INSTANCE
                .getNetworkManager()
                .dispatcher()
                .runtime()
                .supports(ServerboundPackets.CLIENT_TICK_END);
    }

    private boolean hasClientTickEnd() {
        return serverSupportsClientTickEnd
                && !player.isBedrockMovement()
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!hasClientTickEnd()) return;
        if (event.answersTeleport()) return;

        if (!receivedTickEnd
                && flagWithTimerSetback(V.write(verbose()).bool(false).uint(flyingPackets))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            player.onPacketCancel();
            event.cancelFinally();
        }
        receivedTickEnd = false;
        flyingPackets++;
    }

    @CultPacketHandler
    public void onMoveVehicle(
            PacketReceiveEvent<ServerboundMoveVehicle> event, CultPlayer player, ServerboundMoveVehicle packet) {
        if (!hasClientTickEnd()) return;
        // A checked LocalPlayer#tick move is never a teleport answer.
        if (!player.packetStateData.isVehicleMovementFromClientTick()) return;
        // LocalPlayer#tick sends one MoveVehicle for its locally authoritative root, after the passenger Rot.
        // VehicleTimer counts only the first move in a tick, so reject another before it can run prediction.
        if (++vehiclePackets > 1
                && flagWithTimerSetback(V.write(verbose()).bool(false).uint(vehiclePackets))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            event.cancelFinally();
            player.onPacketCancel();
        }
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!hasClientTickEnd()) return;
        if (event.isCancelled()) return;
        receivedTickEnd = true;
        if (flyingPackets > 1
                && flagWithTimerSetback(V.write(verbose()).bool(true).uint(flyingPackets))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            player.onPacketCancel();
        }
        flyingPackets = 0;
        vehiclePackets = 0;
    }

    // A movement beyond the client's tick is a timer violation: a correction tick in its place would hand the
    // client the tick it is trying to gain, on top of the movement the safe position already holds.
    private boolean flagWithTimerSetback(Verbose.Writer verbose) {
        if (!flag(verbose)) return false;
        setbackIfAboveSetbackVLNonSimulating();
        return true;
    }
}
