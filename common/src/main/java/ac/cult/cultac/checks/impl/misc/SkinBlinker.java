package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundClientInformation;
import ac.grim.grimac.api.storage.verbose.Verbose;

/**
 * Skin parts change only from the skin customization screen, and Minecraft#setScreen releases every key mapping, so
 * the client's next tick sends a player input without movement or jump keys before any click in a screen can. Toggled
 * sneak and sprint stay down across screens, so they are not read.
 */
@CheckData(name = "SkinBlinker", stableKey = "cult.misc.skin_blinker", description = "Changed skin parts while moving")
public class SkinBlinker extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("from={sint}, to={sint}");

    private int skinParts = -1;

    public SkinBlinker(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onClientInformation(
            PacketReceiveEvent<ServerboundClientInformation> event,
            CultPlayer player,
            ServerboundClientInformation packet) {
        int previous = skinParts;
        skinParts = packet.information().modelCustomisation();
        if (previous == -1 || previous == skinParts || !player.packetStateData.knownInput.moving()) return;
        flag(V.write(verbose()).sint(previous).sint(skinParts));
    }
}
