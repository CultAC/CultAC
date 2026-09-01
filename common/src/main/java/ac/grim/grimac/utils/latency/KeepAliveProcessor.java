package ac.grim.grimac.utils.latency;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.ping.PingA;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.KeepAliveData;
import ac.grim.grimac.utils.maps.EvictingMap;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.event.PacketSendEvent;
import lombok.Getter;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

public class KeepAliveProcessor extends GrimProcessor implements CheckListener {

    public KeepAliveProcessor(GrimPlayer grimPlayer) { super(grimPlayer); }

    @Getter private final EvictingMap<Long, KeepAliveData> pingMap = new EvictingMap<>(20);

    public long lastKeepAlivePing = -1;

    @GrimPacketHandler
    public void onKeepAlive(PacketReceiveEvent event, GrimPlayer player, ServerboundKeepAlivePacket packet) {
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

    @GrimPacketHandler
    public void onKeepAlive(PacketSendEvent event, GrimPlayer player, ClientboundKeepAlivePacket packet) {
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
