package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.LegacyPacketEventSemantics;
import ac.grim.grimac.checks.type.OrderedPacketReceiveListener;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.protocol.util.WrapperPlayClientPlayerFlying;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "PacketOrderO", stableKey = "grim.packetorder.tick_end_order", description = "Sent packets after movement before the expected client tick end", experimental = true)
public class PacketOrderO extends Check implements OrderedPacketReceiveListener {
    // Raw NMS exposes a namespaced packet type rather than PacketEvents' per-version
    // integer. Preserve the same semantic value with the transport-native string tag.
    private static final Verbose V = Verbose.of("type={str}");

    private boolean flying;

    public PacketOrderO(final GrimPlayer player) {
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

        flag(V.write(verbose()).str(packet.type().id().toString()));
    }
}
