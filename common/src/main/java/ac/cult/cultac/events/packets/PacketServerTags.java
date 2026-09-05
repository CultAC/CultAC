package ac.cult.cultac.events.packets;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;

/** Feeds the per-player client-synced tag store from tag-sync packets. */
public class PacketServerTags {
    @CultPacketHandler
    public void onUpdateTags(PacketSendEvent event, CultPlayer player, ClientboundUpdateTagsPacket packet) {
        final boolean isPlay = event.getConnectionState() == ConnectionProtocol.PLAY;
        if (isPlay) {
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.tagManager.handleTagSync(packet));
        } else {
            // This is during configuration stage, player isn't even in the game yet so no need to lag compensate.
            player.tagManager.handleTagSync(packet);
        }
    }
}
