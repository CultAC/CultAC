package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

import java.util.LinkedList;

@CheckData(name = "BadPacketsO", stableKey = "grim.badpackets.invalid_keepalive", description = "Responded with a keepalive ID that was not sent by the server")
public class BadPacketsO extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("id={slong}");
    private final LinkedList<Long> keepalives = new LinkedList<>();

    public BadPacketsO(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onKeepAlive(PacketSendEvent event, GrimPlayer player, ClientboundKeepAlivePacket packet) {
        keepalives.add(packet.getId());
    }

    @GrimPacketHandler
    public void onKeepAlive(PacketReceiveEvent event, GrimPlayer player, ServerboundKeepAlivePacket packet) {
        long id = packet.getId();
        for (long keepalive : keepalives) {
            if (keepalive == id) {
                Long data;
                do {
                    data = keepalives.poll();
                } while (data != null && data != id);
                return;
            }
        }

        handleInvalidKeepAlive(event, id);
    }

    private void handleInvalidKeepAlive(PacketReceiveEvent event, long id) {
        if (flag(V.write(verbose()).slong(id)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
