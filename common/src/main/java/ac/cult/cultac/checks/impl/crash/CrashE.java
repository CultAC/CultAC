package ac.cult.cultac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;

@CheckData(name = "CrashE", stableKey = "cult.crash.low_view_distance", description = "Sent a client view distance below the minimum allowed value")
public class CrashE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("distance={sint}");

    public CrashE(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onClientInformation(PacketReceiveEvent event, CultPlayer player, ServerboundClientInformationPacket packet) {
        sanitizeClientInformation(event, packet);
    }

    /** Sanitizes the play-state client information handled by this check. */
    public void sanitizeClientInformation(final PacketReceiveEvent event, ServerboundClientInformationPacket packet) {
        ClientInformation information = packet.information();
        int viewDistance = information.viewDistance();
        if (viewDistance < 2) {
            flag(V.write(verbose()).sint(viewDistance));
            // Immutable packets must be replaced before re-encoding.
            ClientInformation fixed = new ClientInformation(
                    information.language(), 2, information.chatVisibility(), information.chatColors(),
                    information.modelCustomisation(), information.mainHand(), information.textFilteringEnabled(),
                    information.allowsListing(), information.particleStatus());
            event.setNmsPacket(new ServerboundClientInformationPacket(fixed));
            event.markForReEncode(true);
        }
    }
}
