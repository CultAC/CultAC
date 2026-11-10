package ac.cult.cultac.network;

import ac.cult.cultac.network.RouteChains.ReceiveChain;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;

public final class PacketReceiveRoute<R> {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PacketReceiveRoute EMPTY = new PacketReceiveRoute(new PacketReceiveHandler[0]);

    private final PacketReceiveHandler<? super R>[] handlers;
    /** Run in order, call {@link #handlers} in order; null when empty or none could be built, and the loop runs them. */
    private final ReceiveChain[] chain;

    private PacketReceiveRoute(PacketReceiveHandler<? super R>[] handlers) {
        this.handlers = handlers;
        chain = RouteChains.receive(handlers);
    }

    public static <R> PacketReceiveRoute<R> of(PacketReceiveHandler<? super R>[] handlers) {
        if (handlers.length == 0) {
            return empty();
        }
        return new PacketReceiveRoute<>(handlers.clone());
    }

    @SuppressWarnings("unchecked")
    public static <R> PacketReceiveRoute<R> empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return handlers.length == 0;
    }

    public void dispatch(PacketReceiveEvent event, CultPlayer player, R packet) {
        if (chain != null) {
            for (ReceiveChain segment : chain) segment.dispatch(event, player, packet);
            return;
        }
        for (PacketReceiveHandler<? super R> handler : handlers) {
            handler.handle(event, player, packet);
        }
    }
}
