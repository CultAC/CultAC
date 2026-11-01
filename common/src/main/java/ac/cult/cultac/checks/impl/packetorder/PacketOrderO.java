package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.util.WrapperPlayClientPlayerFlying;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(
        name = "PacketOrderO",
        stableKey = "cult.packetorder.tick_end_order",
        description = "Sent packets after movement before the expected client tick end",
        experimental = true)
public class PacketOrderO extends Check implements CheckListener {
    // Raw NMS exposes a namespaced packet type; the verbose records that string tag.
    private static final Verbose V = Verbose.of("type={str}");

    private boolean flying;

    public PacketOrderO(final CultPlayer player) {
        super(player);
    }

    // Any packet may intervene between movement and tick end, so this observes the whole stream.
    public void onNonAsyncPacket(PacketReceiveEvent<?> event, CultPlayer player, ServerboundPacket packet) {
        if (event.getPacketType() == ServerboundPackets.CLIENT_TICK_END) {
            flying = false;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event) && !player.packetStateData.lastPacketWasTeleport) {
            flying = true;
            return;
        }

        if (!flying || event.getPacketType() == ServerboundPackets.MOVE_VEHICLE) {
            return;
        }

        if (player.inVehicle() && packet instanceof ServerboundPlayerCommand command) {
            PlayerCommandAction action = command.action();
            if (action == PlayerCommandAction.START_SPRINTING || action == PlayerCommandAction.STOP_SPRINTING) {
                return;
            }
        }

        flag(V.write(verbose()).str(event.getPacketType().key()));
    }
}
