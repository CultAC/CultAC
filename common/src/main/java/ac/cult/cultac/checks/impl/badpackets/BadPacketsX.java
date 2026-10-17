package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.DecodedPacketReceiveListener;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;

@CheckData(
        name = "BadPacketsX",
        stableKey = "cult.badpackets.extra_input_actions",
        description = "Sent duplicate sneak or sprint input actions before the next movement packet",
        experimental = true)
public class BadPacketsX extends Check implements PostPredictionListener, DecodedPacketReceiveListener {
    private boolean sprint;
    private boolean sneak;
    private int flags;

    public BadPacketsX(CultPlayer player) {
        super(player);
    }

    @Override
    public void onDecodedPacketReceive(PacketReceiveEvent event) {
        if (!player.cameraEntity.isSelf()) {
            sprint = sneak = false;
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (flags > 0) {
            setbackIfAboveSetbackVL();
        }

        flags = 0;
    }

    @CultPacketHandler
    public void onPlayerCommand(
            PacketReceiveEvent<ServerboundPlayerCommand> event, CultPlayer player, ServerboundPlayerCommand packet) {
        if (!player.cameraEntity.isSelf()) {
            sprint = sneak = false;
            return;
        }

        switch (packet.action()) {
            case PRESS_SHIFT_KEY, RELEASE_SHIFT_KEY -> {
                if (DecodedPacketReliability.nativeInputFamilyReliable(
                        player.getClientVersion(), player.getObservedProtocol())) {
                    if (sneak && flag()) {
                        flags++;
                    }
                    sneak = true;
                }
            }
            case START_SPRINTING, STOP_SPRINTING -> {
                if (player.inVehicle()) {
                    return;
                }

                if (sprint) {
                    if (flag()) {
                        flags++;
                    }
                }

                sprint = true;
            }
            default -> {}
        }
    }

    // isTickPacket: movement packets reset unless they answered a teleport
    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.lastPacketWasTeleport) {
            sprint = sneak = false;
        }
    }

    // isTickPacket: tick end resets for 1.21.2+ clients when no movement arrived this client tick
    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        if (!player.cameraEntity.isSelf() || !player.packetStateData.receivedMovementThisClientTick) {
            sprint = sneak = false;
        }
    }
}
