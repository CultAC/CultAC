package ac.cult.cultac.checks.impl.visibility;

import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.utils.visibility.ClientVisibilityProcessor;

@CheckData(
        name = "PlaceVisibility",
        stableKey = "cult.place.visibility",
        description = "Placed or interacted with a block through blocks or on an inaccessible face")
public final class PlaceVisibility extends VisibilityCheck {
    public PlaceVisibility(CultPlayer player, ClientVisibilityProcessor visibility) {
        super(player, visibility);
    }

    @CultPacketHandler
    public void onUseItemOn(
            PacketReceiveEvent<ServerboundUseItemOn> event, CultPlayer player, ServerboundUseItemOn packet) {
        if (visibility.blockFaceHidden(packet.blockPosition(), packet.blockFace()))
            reject(event, block(packet.blockPosition(), packet.blockFace()));
    }
}
