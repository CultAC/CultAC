package ac.cult.cultac.network;

import ac.cult.cultac.network.RouteChains.ReceiveChain;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;

/** A template: {@link RouteChains} runs hidden clones of it, up to {@link RouteChains#WIDTH} handlers each. */
@SuppressWarnings({"rawtypes", "unchecked", "fallthrough"})
final class ReceiveSegment extends ReceiveChain {
    private final PacketReceiveHandler h0, h1, h2, h3, h4, h5, h6, h7;
    /** Leading sites left empty; the switch enters at the first used one. */
    private final int skip;

    ReceiveSegment(PacketReceiveHandler[] handlers, int from, int count) {
        skip = RouteChains.WIDTH - count;
        h0 = RouteChains.slot(handlers, from, count, 0);
        h1 = RouteChains.slot(handlers, from, count, 1);
        h2 = RouteChains.slot(handlers, from, count, 2);
        h3 = RouteChains.slot(handlers, from, count, 3);
        h4 = RouteChains.slot(handlers, from, count, 4);
        h5 = RouteChains.slot(handlers, from, count, 5);
        h6 = RouteChains.slot(handlers, from, count, 6);
        h7 = RouteChains.slot(handlers, from, count, 7);
    }

    @Override
    void dispatch(PacketReceiveEvent<?> event, CultPlayer player, Object packet) {
        switch (skip) {
            case 0:
                h0.handle(event, player, packet);
            case 1:
                h1.handle(event, player, packet);
            case 2:
                h2.handle(event, player, packet);
            case 3:
                h3.handle(event, player, packet);
            case 4:
                h4.handle(event, player, packet);
            case 5:
                h5.handle(event, player, packet);
            case 6:
                h6.handle(event, player, packet);
            case 7:
                h7.handle(event, player, packet);
            default:
        }
    }
}
