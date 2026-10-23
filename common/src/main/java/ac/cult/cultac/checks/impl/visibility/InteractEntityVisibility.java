package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

/** Minecraft#startUseItem uses the cached entity pick, then checks entity interaction range. */
@CheckData(
        name = "InteractEntityVisibility",
        stableKey = "cult.interact.entity.visibility",
        description = "Interacted with an entity through blocks or outside interaction range")
public final class InteractEntityVisibility extends VisibilityCheck {
    public InteractEntityVisibility(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility);
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() != InteractAction.INTERACT && packet.action() != InteractAction.INTERACT_AT) return;
        var entity = player.compensatedEntities.getEntity(packet.entityId());
        if (entity == null) return;
        if (visibility.entityInteractionHidden(entity))
            reject(event, "action=" + packet.action() + ", " + type(entity));
    }
}
