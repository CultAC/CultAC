package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "PacketOrderH", stableKey = "grim.packetorder.sneak_sprint_order", description = "Sent sprinting and sneaking state changes in an invalid packet order", experimental = true)
public class PacketOrderH extends Check implements PostPredictionListener {
    public PacketOrderH(final GrimPlayer player) {
        super(player);
    }

    private int invalid;

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        switch (NmsPacketUtil.readPlayerCommand(packet).action()) {
            case START_SPRINTING, STOP_SPRINTING -> {
                if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2) && player.packetOrderProcessor.isSneaking()) {
                    if (!player.canSkipTicks()) {
                        flag();
                    } else {
                        invalid++;
                    }
                }
            }

            case PRESS_SHIFT_KEY, RELEASE_SHIFT_KEY -> {
                if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2) && player.packetOrderProcessor.isSprinting()) {
                    if (!player.canSkipTicks()) {
                        flag();
                    } else {
                        invalid++;
                    }
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!player.canSkipTicks()) return;

        if (player.isTickingReliablyFor(3)) {
            for (; invalid >= 1; invalid--) {
                flag();
            }
        }

        invalid = 0;
    }
}
