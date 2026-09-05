package ac.cult.cultac.network.event;

import ac.cult.cultac.network.protocol.player.User;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public abstract class PacketEvent {
    private final User user;
    private Packet<?> packet;
    private final ConnectionProtocol connectionState;
    private final long timestamp = System.currentTimeMillis();
    private final boolean insideBundle;
    private final List<Packet<?>> packetsBeforeSend = new ArrayList<>(1);
    private final List<Packet<?>> packetsAfterSend = new ArrayList<>(2);
    private final List<Runnable> tasksAfterSend = new ArrayList<>(2);
    private final List<Runnable> postTasks = new ArrayList<>(2);
    private boolean cancelled;
    private boolean reEncode;
    private Object lastUsedWrapper;
    private ByteBuf byteBuf;

    protected PacketEvent(User user, Packet<?> packet, ConnectionProtocol connectionState) {
        this(user, packet, connectionState, false);
    }

    protected PacketEvent(User user, Packet<?> packet, ConnectionProtocol connectionState, boolean insideBundle) {
        this.user = user;
        this.packet = packet;
        this.connectionState = connectionState;
        this.insideBundle = insideBundle;
    }

    public User getUser() {
        return user;
    }

    public Player getPlayer() {
        return user == null ? null : user.getPlayer();
    }

    public Packet<?> getNmsPacket() {
        return packet;
    }

    public void setNmsPacket(Packet<?> packet) {
        this.packet = packet;
    }

    public ConnectionProtocol getConnectionState() {
        return connectionState;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isInsideBundle() {
        return insideBundle;
    }

    public List<Packet<?>> getPacketsBeforeSend() {
        return packetsBeforeSend;
    }

    public List<Packet<?>> getPacketsAfterSend() {
        return packetsAfterSend;
    }

    public List<Runnable> getTasksAfterSend() {
        return tasksAfterSend;
    }

    public List<Runnable> getPostTasks() {
        return postTasks;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public void markForReEncode(boolean reEncode) {
        this.reEncode = reEncode;
    }

    public boolean shouldReEncode() {
        return reEncode;
    }

    public Object getLastUsedWrapper() {
        return lastUsedWrapper;
    }

    public void setLastUsedWrapper(Object lastUsedWrapper) {
        this.lastUsedWrapper = lastUsedWrapper;
    }

    public ByteBuf getByteBuf() {
        return byteBuf;
    }

    public void setByteBuf(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;
    }
}
