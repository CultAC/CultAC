package ac.cult.cultac.network;

import ac.cult.cultac.network.RouteChains.SendChain;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;

public final class PacketSendRoute<R> {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PacketSendRoute EMPTY = new PacketSendRoute(new PacketSendHandler[0], new PacketSendHandler[0]);

    private final PacketSendHandler<? super R>[] handlers;
    /** The subset of {@link #handlers} that also runs for connections without a player. */
    private final PacketSendHandler<? super R>[] connectionHandlers;
    /** Run in order, call their arrays in order; null when empty or none could be built, and the loop runs them. */
    private final SendChain[] chain, connectionChain;

    private PacketSendRoute(
            PacketSendHandler<? super R>[] handlers, PacketSendHandler<? super R>[] connectionHandlers) {
        this.handlers = handlers;
        this.connectionHandlers = connectionHandlers;
        chain = RouteChains.send(handlers);
        connectionChain = RouteChains.send(connectionHandlers);
    }

    /** {@code connectionHandlers} must be a subset of {@code handlers}. */
    public static <R> PacketSendRoute<R> of(
            PacketSendHandler<? super R>[] handlers, PacketSendHandler<? super R>[] connectionHandlers) {
        if (handlers.length == 0) {
            return empty();
        }
        return new PacketSendRoute<>(handlers.clone(), connectionHandlers.clone());
    }

    @SuppressWarnings("unchecked")
    public static <R> PacketSendRoute<R> empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return handlers.length == 0;
    }

    public boolean hasConnectionHandlers() {
        return connectionHandlers.length != 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void dispatch(PacketSendEvent<?> event, CultPlayer player, R packet) {
        if (chain != null) {
            for (SendChain segment : chain) segment.dispatch(event, player, packet);
            return;
        }
        for (PacketSendHandler<? super R> handler : handlers) {
            handler.handle((PacketSendEvent) event, player, packet);
        }
    }

    /** Runs the connection handlers for a connection that has no player; they receive a null player. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void dispatchConnection(PacketSendEvent<?> event, R packet) {
        if (connectionChain != null) {
            for (SendChain segment : connectionChain) segment.dispatch(event, null, packet);
            return;
        }
        for (PacketSendHandler<? super R> handler : connectionHandlers) {
            handler.handle((PacketSendEvent) event, null, packet);
        }
    }
}
