package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "PacketOrderH", stableKey = "cult.packetorder.sneak_sprint_order", description = "Sent sprinting and sneaking state changes in an invalid packet order", experimental = true)
public class PacketOrderH extends Check implements PostPredictionListener {
    public PacketOrderH(final CultPlayer player) {
        super(player);
    }

    private int invalid;

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
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
