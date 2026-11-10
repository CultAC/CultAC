package ac.cult.cultac.network;

import ac.cult.cultac.network.PacketHandlerScanner.GatedReceive;
import ac.cult.cultac.network.PacketHandlerScanner.GatedSend;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Straight-line handler chains. A chain is a sequence of segments, each an instance of a hidden clone of a template,
 * one clone per position and call-site tuple, so each call site in it has its own compiled code and type profile.
 * Gated scanned handlers run in gated segments, which call their invokers directly; any other handler runs in a plain
 * segment through {@code handle}.
 */
final class RouteChains {
    static final int WIDTH = 8;
    /** Distinct clones per template; past it, segments at one position share a clone. */
    private static final int MAX_CLONES = 1024;

    private static final MethodType RECEIVE_CONSTRUCTOR =
            MethodType.methodType(ReceiveChain.class, PacketReceiveHandler[].class, int.class, int.class);
    private static final MethodType SEND_CONSTRUCTOR =
            MethodType.methodType(SendChain.class, PacketSendHandler[].class, int.class, int.class);
    private static final Segments RECEIVE = new Segments(ReceiveSegment.class, RECEIVE_CONSTRUCTOR);
    private static final Segments GATED_RECEIVE = new Segments(GatedReceiveSegment.class, RECEIVE_CONSTRUCTOR);
    private static final Segments SEND = new Segments(SendSegment.class, SEND_CONSTRUCTOR);
    private static final Segments GATED_SEND = new Segments(GatedSendSegment.class, SEND_CONSTRUCTOR);

    private RouteChains() {}

    abstract static class ReceiveChain {
        abstract void dispatch(PacketReceiveEvent<?> event, CultPlayer player, Object packet);
    }

    abstract static class SendChain {
        abstract void dispatch(PacketSendEvent<?> event, CultPlayer player, Object packet);
    }

    /** Segments calling {@code handlers} in order when run in order, or null when no clone can be defined. */
    static ReceiveChain[] receive(PacketReceiveHandler<?>[] handlers) {
        if (handlers.length == 0) return null;
        try {
            List<ReceiveChain> chain = new ArrayList<>();
            for (int from = 0; from < handlers.length; ) {
                boolean gated = handlers[from] instanceof GatedReceive;
                int count = run(handlers, from, GatedReceive.class);
                var segments = gated ? GATED_RECEIVE : RECEIVE;
                if (!segments.available) return null;
                chain.add((ReceiveChain) segments.constructor(handlers, from, count, chain.size())
                        .invokeExact((PacketReceiveHandler[]) handlers, from, count));
                from += count;
            }
            return chain.toArray(ReceiveChain[]::new);
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    /** Segments calling {@code handlers} in order when run in order, or null when no clone can be defined. */
    static SendChain[] send(PacketSendHandler<?>[] handlers) {
        if (handlers.length == 0) return null;
        try {
            List<SendChain> chain = new ArrayList<>();
            for (int from = 0; from < handlers.length; ) {
                boolean gated = handlers[from] instanceof GatedSend;
                int count = run(handlers, from, GatedSend.class);
                var segments = gated ? GATED_SEND : SEND;
                if (!segments.available) return null;
                chain.add((SendChain) segments.constructor(handlers, from, count, chain.size())
                        .invokeExact((PacketSendHandler[]) handlers, from, count));
                from += count;
            }
            return chain.toArray(SendChain[]::new);
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    /** How many handlers from {@code from}, up to {@link #WIDTH}, share whether they are a {@code gated}. */
    private static int run(Object[] handlers, int from, Class<?> gated) {
        boolean first = gated.isInstance(handlers[from]);
        int count = 1;
        while (count < WIDTH && from + count < handlers.length && gated.isInstance(handlers[from + count]) == first)
            count++;
        return count;
    }

    /** The handler a segment of {@code count} handlers from {@code from} calls at {@code site}, or null. */
    static <H> H slot(H[] handlers, int from, int count, int site) {
        int index = site - (WIDTH - count);
        return index < 0 ? null : handlers[from + index];
    }

    /** What a call site's type profile sees: the invoker and gate classes of a gated handler, else its class. */
    private static String site(Object handler) {
        if (handler instanceof GatedReceive gated) return gated(gated.invoker, gated.gate);
        if (handler instanceof GatedSend gated) return gated(gated.invoker, gated.gate);
        return handler.getClass().getName();
    }

    private static String gated(Object invoker, Object gate) {
        return invoker.getClass().getName()
                + '?'
                + (gate == null ? "" : gate.getClass().getName());
    }

    private static final class Segments {
        private final Class<?> template;
        private final MethodType constructorType;
        private final Map<String, MethodHandle> clones = new HashMap<>();
        private final List<MethodHandle> shared = new ArrayList<>();
        private byte[] bytes;
        /** False once a clone failed to define; routes then run their handler loops. */
        volatile boolean available = true;

        Segments(Class<?> template, MethodType constructorType) {
            this.template = template;
            this.constructorType = constructorType;
        }

        synchronized MethodHandle constructor(Object[] handlers, int from, int count, int position)
                throws ReflectiveOperationException, IOException {
            if (!available) throw new IOException(template.getName() + " cannot be cloned");
            // Class names, not classes: the key must not keep a listener's class loader alive.
            var builder = new StringBuilder().append(position);
            for (int i = from; i < from + count; i++) builder.append(',').append(site(handlers[i]));
            String key = builder.toString();
            MethodHandle clone = clones.get(key);
            if (clone != null) return clone;
            if (clones.size() < MAX_CLONES) {
                clone = define();
                clones.put(key, clone);
                return clone;
            }
            while (shared.size() <= position) shared.add(null);
            clone = shared.get(position);
            if (clone == null) shared.set(position, clone = define());
            return clone;
        }

        private MethodHandle define() throws ReflectiveOperationException, IOException {
            try {
                if (bytes == null) {
                    try (InputStream in = template.getResourceAsStream(template.getSimpleName() + ".class")) {
                        if (in == null) throw new IOException("Missing class bytes of " + template.getName());
                        bytes = in.readAllBytes();
                    }
                }
                var lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
                return lookup.findConstructor(lookup.lookupClass(), constructorType.changeReturnType(void.class))
                        .asType(constructorType);
            } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
                available = false;
                try {
                    LogUtil.warn(
                            "Packet routes cannot clone " + template.getSimpleName()
                                    + "; they call their handlers through a loop",
                            failure);
                } catch (RuntimeException ignored) {
                    // No logger before the platform starts; the loop is the same dispatch.
                }
                throw failure;
            }
        }
    }
}
