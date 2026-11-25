package ac.cult.cultac.checks.impl.visibility;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.FormerName;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/**
 * {@link InteractEntityVisibility} for a player target. The two were one check, InteractEntityVisibility, up to name
 * version 1, so a file written in that version names, sets and exempts both with that name.
 */
@CheckData(
        name = "InteractPlayerVisibility",
        formerNames = @FormerName(name = "InteractEntityVisibility", until = 1),
        stableKey = "cult.interact.player.visibility",
        description = "Interacted with a player through blocks or outside interaction range")
public final class InteractPlayerVisibility extends VisibilityCheck {
    public InteractPlayerVisibility(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() != InteractAction.INTERACT && packet.action() != InteractAction.INTERACT_AT) return;
        var entity = player.compensatedEntities.getEntity(packet.entityId());
        if (entity == null || entity.type != EntityTypeIds.PLAYER) return;
        if (visibility.entityInteractionHidden(entity)) reject(event, "action=" + packet.action());
    }
}
