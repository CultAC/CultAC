package ac.cult.cultac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

import java.util.LinkedList;

@CheckData(name = "BadPacketsO", stableKey = "cult.badpackets.invalid_keepalive", description = "Responded with a keepalive ID that was not sent by the server")
public class BadPacketsO extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("id={slong}");
    private final LinkedList<Long> keepalives = new LinkedList<>();

    public BadPacketsO(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onKeepAlive(PacketSendEvent event, CultPlayer player, ClientboundKeepAlivePacket packet) {
        keepalives.add(packet.getId());
    }

    @CultPacketHandler
    public void onKeepAlive(PacketReceiveEvent event, CultPlayer player, ServerboundKeepAlivePacket packet) {
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
