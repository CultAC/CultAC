package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundKeepAlive;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundKeepAlive;
import ac.grim.grimac.api.storage.verbose.Verbose;
import java.util.LinkedList;

@CheckData(
        name = "BadPacketsO",
        stableKey = "cult.badpackets.invalid_keepalive",
        description = "Responded with a keepalive ID that was not sent by the server")
public class BadPacketsO extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("id={slong}");
    private final LinkedList<PendingKeepAlive> keepalives = new LinkedList<>();

    private static final class PendingKeepAlive {
        private final long id;
        // Zero until KeepAliveProcessor has written the challenge's trailing ping.
        private int transaction;

        private PendingKeepAlive(long id) {
            this.id = id;
        }
    }

    public BadPacketsO(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onKeepAlive(
            PacketSendEvent<ClientboundKeepAlive> event, CultPlayer player, ClientboundKeepAlive packet) {
        PendingKeepAlive pending = new PendingKeepAlive(packet.id());
        keepalives.add(pending);
        // KeepAliveProcessor runs first and publishes its actual trailing ping in its after-send task.
        event.getTasksAfterSend().add(() -> pending.transaction = event.receiptProof());
    }

    @CultPacketHandler
    public void onKeepAlive(
            PacketReceiveEvent<ServerboundKeepAlive> event, CultPlayer player, ServerboundKeepAlive packet) {
        long id = packet.id();
        var iterator = keepalives.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id == id) {
                // ClientCommonPacketListenerImpl#sendWhen can send a newer reply immediately while
                // an older one awaits ClientPacketListener#tick's sendDeferredPackets().
                iterator.remove();
                return;
            }
        }

        handleInvalidKeepAlive(event, id);
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        int received = player.lastTransactionReceived.get();
        // handlePing runs on the client thread between ticks. Its pong proves the preceding
        // keepalive was handled, but a deferred reply can still follow that pong. The next
        // Minecraft#tick calls sendDeferredPackets before sending ClientTickEnd; pollEvents
        // cannot be frozen during that tick. Only this boundary retires unanswered challenges.
        // Keep unproven challenges: a tick can finish before the client receives them.
        keepalives.removeIf(pending -> pending.transaction > 0 && pending.transaction <= received);
    }

    private void handleInvalidKeepAlive(PacketReceiveEvent event, long id) {
        if (flag(V.write(verbose()).slong(id)) && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }
}
