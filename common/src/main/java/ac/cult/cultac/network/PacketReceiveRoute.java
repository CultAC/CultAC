package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;

public final class PacketReceiveRoute {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PacketReceiveRoute EMPTY = new PacketReceiveRoute(new PacketReceiveHandler[0]);

    private final PacketReceiveHandler<Packet<?>>[] handlers;

    private PacketReceiveRoute(PacketReceiveHandler<Packet<?>>[] handlers) {
        this.handlers = handlers;
    }

    public static PacketReceiveRoute of(PacketReceiveHandler<Packet<?>>[] handlers) {
        if (handlers.length == 0) {
            return EMPTY;
        }
        return new PacketReceiveRoute(handlers.clone());
    }

    public boolean isEmpty() {
        return handlers.length == 0;
    }

    public void dispatch(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
        for (PacketReceiveHandler<Packet<?>> handler : handlers) {
            handler.handle(event, player, packet);
        }
    }
}
