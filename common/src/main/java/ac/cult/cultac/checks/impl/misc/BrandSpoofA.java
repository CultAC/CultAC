package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.ClientClassification;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;

@CheckData(
        name = "BrandSpoofA",
        stableKey = "cult.misc.brand_spoof_a",
        description = "Reported conflicting client information")
public final class BrandSpoofA extends Check implements CheckListener {
    private boolean reported;

    public BrandSpoofA(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onMove(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        if (reported || player.isBedrockMovement()) return;
        if (!"vanilla".equals(player.getBrand())) return;
        var channel = player.pluginChannelManager.getChannelType();
        if (channel != ClientClassification.Type.FABRIC) return;
        var identity = player.getClientClassification();
        if (!player.checkManager.getCheck(ClientBrand.class).isHasBrand() || !identity.contradictsVanilla()) return;
        reported = true;
        flag(identity.display());
    }
}
