package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundBlockEvent;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.data.ShulkerData;
import ac.cult.cultac.utils.nmsutil.ClientBlockProperties;

// If a player doesn't get this packet, then they don't know the shulker box is currently opened
// Meaning if a player enters a chunk with an opened shulker box, they see the shulker box as closed.
//
// Exempting the player on shulker boxes is an option... but then you have people creating PvP arenas
// on shulker boxes to get high lenience.
//
public class PacketBlockAction {
    // HIGH

    @CultPacketHandler
    public void onBlockEvent(
            PacketSendEvent<ClientboundBlockEvent> event, CultPlayer player, ClientboundBlockEvent packet) {
        BlockPos blockPos = new BlockPos(
                packet.position().x(), packet.position().y(), packet.position().z());

        // The client ignores the state sent to the client.
        player.latencyUtils.addRealTimeTaskNow(() -> {
            int existing = player.compensatedWorld.getBlockStateIdAt(blockPos);
            if (ClientBlockProperties.isShulkerBox(existing)) {
                // Param is the number of viewers of the shulker box.
                // Hashset with .equals() set to be position
                int action = packet.action();
                if (action == 1) {
                    final ShulkerData openedBox = new ShulkerData(blockPos, player.lastTransactionSent.get(), false);
                    player.compensatedWorld.openShulkerBoxes.remove(openedBox);
                    player.compensatedWorld.openShulkerBoxes.add(openedBox);
                } else if (action == 0) {
                    // The shulker box is closing
                    final ShulkerData closingBox = new ShulkerData(blockPos, player.lastTransactionSent.get(), true);
                    player.compensatedWorld.openShulkerBoxes.remove(closingBox);
                    player.compensatedWorld.openShulkerBoxes.add(closingBox);
                }
                // MCP-Reborn ShulkerBoxBlockEntity#triggerEvent only changes
                // animation status for viewer counts 0 and 1. Higher counts
                // update openCount but do not restart the opening shove.
            }
        });
    }
}
