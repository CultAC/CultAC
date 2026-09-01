package ac.grim.grimac.checks.impl.crash;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;

@CheckData(name = "CrashE", stableKey = "grim.crash.low_view_distance", description = "Sent a client view distance below the minimum allowed value")
public class CrashE extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("distance={sint}");

    public CrashE(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onClientInformation(PacketReceiveEvent event, GrimPlayer player, ServerboundClientInformationPacket packet) {
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
