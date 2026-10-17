package ac.cult.cultac.checks.impl.movement.timer;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "TickTimer",
        stableKey = "cult.timer.tick",
        description = "Did not send client tick end packet",
        setback = 1)
public final class TickTimer extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("type=[end|flying], packets={uint}");

    private boolean receivedTickEnd = true;
    private int flyingPackets;

    public TickTimer(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (player.packetStateData.lastPacketWasTeleport) return;

        if (!receivedTickEnd
                && flagWithSetback(V.write(verbose()).bool(false).uint(flyingPackets))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            player.onPacketCancel();
            if (player.shouldEnforceMovementSetbacks()
                    && player.getSetbackTeleportUtil().shouldBlockMovement()) {
                event.setCancelled(true);
            }
        }
        receivedTickEnd = false;
        flyingPackets++;
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        receivedTickEnd = true;
        if (flyingPackets > 1
                && flagWithSetback(V.write(verbose()).bool(true).uint(flyingPackets))
                && !CultAPI.INSTANCE.getConfigManager().isObserveOnly()) {
            player.onPacketCancel();
        }
        flyingPackets = 0;
    }
}
