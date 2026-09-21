package ac.cult.cultac.bedrock.bridge;

import ac.cult.cultac.player.CultPlayer;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.geyser.session.GeyserSession;

final class GeyserEntityInteractions {
    private GeyserEntityInteractions() { }

    static void observe(GeyserSession session, CultPlayer player, InventoryTransactionPacket packet) {
        var entity = session.getEntityCache().getEntityByGeyserId(packet.getRuntimeEntityId());
        if (entity != null && packet.getActionType() == 1) player.actionManager.attack(entity.getEntityId());
    }
}
