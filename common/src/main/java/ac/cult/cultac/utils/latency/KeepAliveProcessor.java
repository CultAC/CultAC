package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.ping.PingA;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.KeepAliveData;
import ac.cult.cultac.utils.maps.EvictingMap;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import lombok.Getter;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

public class KeepAliveProcessor extends CultProcessor implements CheckListener {

    public KeepAliveProcessor(CultPlayer cultPlayer) { super(cultPlayer); }

    @Getter private final EvictingMap<Long, KeepAliveData> pingMap = new EvictingMap<>(20);

    public long lastKeepAlivePing = -1;

    @CultPacketHandler
    public void onKeepAlive(PacketReceiveEvent event, CultPlayer player, ServerboundKeepAlivePacket packet) {
        long id = packet.getId();
        final KeepAliveData data = this.pingMap.get(id);
        if (data != null && data.getTimeReceived() == 0) { final long time = System.currentTimeMillis();
            this.lastKeepAlivePing = Math.max(1, time - data.getTimeSent()); data.setTimeReceived(time);
            final long diff = time - data.getTransReceived();
            // some clients when tabbed out won't send transaction packets, so
            // if the player hasn't rotated in the past 20 seconds, ignore them
            final long lastRotated = time - player.lastRotated;
            //
            debug(() -> "diff=" + diff + " ping=" + this.lastKeepAlivePing + " lr=" + lastRotated);
            if (diff > 25 && lastRotated < 20000 && data.getTransReceived() != 0) {
                player.checkManager.getCheck(PingA.class).flag("diff=" + diff + "ms" + " lr=" + lastRotated + "ms"); //flag for delaying keep alive packets
            }
        } else {
            final String invalidKind = data == null ? "invalid" : "dupe";
            debug(() -> "rejected=" + invalidKind + " id=" + id);
        }
    }

    @CultPacketHandler
    public void onKeepAlive(PacketSendEvent event, CultPlayer player, ClientboundKeepAlivePacket packet) {
        long id = packet.getId();
        final KeepAliveData keepAliveData = new KeepAliveData(id, System.currentTimeMillis());
        this.pingMap.put(id, keepAliveData);
        //sandwich the keep alive packet
        player.sendTransaction();
        player.latencyUtils.addRealTimeTaskNext(() -> { final long receivedAt = System.currentTimeMillis();
            keepAliveData.setTransReceived(receivedAt);
        });
        event.getTasksAfterSend().add(player::sendTransaction);
    }
}
