package ac.cult.cultac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.LegacyPacketEventSemantics;
import ac.cult.cultac.checks.type.OrderedPacketReceiveListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.util.WrapperPlayClientPlayerFlying;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.nmsutil.NmsIdentifierUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "PacketOrderO", stableKey = "cult.packetorder.tick_end_order", description = "Sent packets after movement before the expected client tick end", experimental = true)
public class PacketOrderO extends Check implements OrderedPacketReceiveListener {
    // Raw NMS exposes a namespaced packet type rather than PacketEvents' per-version
    // integer. Preserve the same semantic value with the transport-native string tag.
    private static final Verbose V = Verbose.of("type={str}");

    private boolean flying;

    public PacketOrderO(final CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.supportsEndTick();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!isApplicable()) return;

        Packet<?> packet = event.getNmsPacket();
        if (packet instanceof ServerboundClientTickEndPacket) {
            flying = false;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event)
                && !player.packetStateData.lastPacketWasTeleport) {
            flying = true;
            return;
        }

        if (!flying || LegacyPacketEventSemantics.isAsync(packet)
                || packet instanceof ServerboundMoveVehiclePacket) {
            return;
        }

        if (player.inVehicle() && packet instanceof ServerboundPlayerCommandPacket command) {
            ServerboundPlayerCommandPacket.Action action = command.getAction();
            if (action == ServerboundPlayerCommandPacket.Action.START_SPRINTING
                    || action == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) {
                return;
            }
        }

        flag(V.write(verbose()).str(NmsIdentifierUtil.packetTypeId(packet.type())));
    }
}
