package ac.cult.cultac.network;

import ac.cult.cultac.network.PacketHandlerScanner.GatedReceive;
import ac.cult.cultac.network.RouteChains.ReceiveChain;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;

/**
 * A template: {@link RouteChains} runs hidden clones of it, up to {@link RouteChains#WIDTH} gated handlers each, so
 * each site tests its own gate and calls its own invoker with the failure handling of {@link GatedReceive}.
 */
@SuppressWarnings({"rawtypes", "fallthrough"})
final class GatedReceiveSegment extends ReceiveChain {
    private final GatedReceive s0, s1, s2, s3, s4, s5, s6, s7;
    /** Leading sites left empty; the switch enters at the first used one. */
    private final int skip;

    GatedReceiveSegment(PacketReceiveHandler[] handlers, int from, int count) {
        skip = RouteChains.WIDTH - count;
        s0 = (GatedReceive) RouteChains.slot(handlers, from, count, 0);
        s1 = (GatedReceive) RouteChains.slot(handlers, from, count, 1);
        s2 = (GatedReceive) RouteChains.slot(handlers, from, count, 2);
        s3 = (GatedReceive) RouteChains.slot(handlers, from, count, 3);
        s4 = (GatedReceive) RouteChains.slot(handlers, from, count, 4);
        s5 = (GatedReceive) RouteChains.slot(handlers, from, count, 5);
        s6 = (GatedReceive) RouteChains.slot(handlers, from, count, 6);
        s7 = (GatedReceive) RouteChains.slot(handlers, from, count, 7);
    }

    @Override
    void dispatch(PacketReceiveEvent<?> event, CultPlayer player, Object packet) {
        switch (skip) {
            case 0:
                if (s0.gate == null || s0.gate.getAsBoolean()) {
                    try {
                        s0.invoker.call(s0.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s0.method, failure);
                    }
                }
            case 1:
                if (s1.gate == null || s1.gate.getAsBoolean()) {
                    try {
                        s1.invoker.call(s1.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s1.method, failure);
                    }
                }
            case 2:
                if (s2.gate == null || s2.gate.getAsBoolean()) {
                    try {
                        s2.invoker.call(s2.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s2.method, failure);
                    }
                }
            case 3:
                if (s3.gate == null || s3.gate.getAsBoolean()) {
                    try {
                        s3.invoker.call(s3.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s3.method, failure);
                    }
                }
            case 4:
                if (s4.gate == null || s4.gate.getAsBoolean()) {
                    try {
                        s4.invoker.call(s4.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s4.method, failure);
                    }
                }
            case 5:
                if (s5.gate == null || s5.gate.getAsBoolean()) {
                    try {
                        s5.invoker.call(s5.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s5.method, failure);
                    }
                }
            case 6:
                if (s6.gate == null || s6.gate.getAsBoolean()) {
                    try {
                        s6.invoker.call(s6.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s6.method, failure);
                    }
                }
            case 7:
                if (s7.gate == null || s7.gate.getAsBoolean()) {
                    try {
                        s7.invoker.call(s7.listener, event, player, packet);
                    } catch (Throwable failure) {
                        throw PacketHandlerScanner.invocationFailure(s7.method, failure);
                    }
                }
            default:
        }
    }
}
