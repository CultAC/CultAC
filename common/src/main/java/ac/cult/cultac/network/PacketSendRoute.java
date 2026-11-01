package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;

public final class PacketSendRoute<R> {
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static final PacketSendRoute EMPTY = new PacketSendRoute(new PacketSendHandler[0], new PacketSendHandler[0]);

    private final PacketSendHandler<? super R>[] handlers;
    /** The subset of {@link #handlers} that also runs for connections without a player. */
    private final PacketSendHandler<? super R>[] connectionHandlers;

    private PacketSendRoute(
            PacketSendHandler<? super R>[] handlers, PacketSendHandler<? super R>[] connectionHandlers) {
        this.handlers = handlers;
        this.connectionHandlers = connectionHandlers;
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
        for (PacketSendHandler<? super R> handler : handlers) {
            handler.handle((PacketSendEvent) event, player, packet);
        }
    }

    /** Runs the connection handlers for a connection that has no player; they receive a null player. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void dispatchConnection(PacketSendEvent<?> event, R packet) {
        for (PacketSendHandler<? super R> handler : connectionHandlers) {
            handler.handle((PacketSendEvent) event, null, packet);
        }
    }
}
