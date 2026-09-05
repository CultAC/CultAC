package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

public final class PacketSendRoute {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PacketSendRoute EMPTY = new PacketSendRoute(new PacketSendHandler[0]);

    private final PacketSendHandler<Packet<?>>[] handlers;

    private PacketSendRoute(PacketSendHandler<Packet<?>>[] handlers) {
        this.handlers = handlers;
    }

    public static PacketSendRoute of(PacketSendHandler<Packet<?>>[] handlers) {
        if (handlers.length == 0) {
            return EMPTY;
        }
        return new PacketSendRoute(handlers.clone());
    }

    public boolean isEmpty() {
        return handlers.length == 0;
    }

    public void dispatch(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
        for (PacketSendHandler<Packet<?>> handler : handlers) {
            handler.handle(event, player, packet);
        }
    }
}
