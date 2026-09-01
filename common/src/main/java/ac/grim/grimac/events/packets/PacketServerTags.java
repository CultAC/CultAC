package ac.grim.grimac.events.packets;

import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;

/** Feeds the per-player client-synced tag store from tag-sync packets. */
public class PacketServerTags {
    @GrimPacketHandler
    public void onUpdateTags(PacketSendEvent event, GrimPlayer player, ClientboundUpdateTagsPacket packet) {
        final boolean isPlay = event.getConnectionState() == ConnectionProtocol.PLAY;
        if (isPlay) {
            player.latencyUtils.addRealTimeTask(player.lastTransactionSent.get(), () -> player.tagManager.handleTagSync(packet));
        } else {
            // This is during configuration stage, player isn't even in the game yet so no need to lag compensate.
            player.tagManager.handleTagSync(packet);
        }
    }
}
