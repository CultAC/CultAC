package ac.cult.cultac.network;

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
 * one clone per position and handler-class tuple, so each call site in it has its own compiled code and type profile.
 */
final class RouteChains {
    static final int WIDTH = 8;
    /** Distinct clones per template; past it, segments at one position share a clone. */
    private static final int MAX_CLONES = 1024;

    private static final Segments RECEIVE = new Segments(
            ReceiveSegment.class,
            MethodType.methodType(ReceiveChain.class, PacketReceiveHandler[].class, int.class, int.class));
    private static final Segments SEND = new Segments(
            SendSegment.class, MethodType.methodType(SendChain.class, PacketSendHandler[].class, int.class, int.class));

    private RouteChains() {}

    abstract static class ReceiveChain {
        abstract void dispatch(PacketReceiveEvent<?> event, CultPlayer player, Object packet);
    }

    abstract static class SendChain {
        abstract void dispatch(PacketSendEvent<?> event, CultPlayer player, Object packet);
    }

    /** Segments calling {@code handlers} in order when run in order, or null when no clone can be defined. */
    static ReceiveChain[] receive(PacketReceiveHandler<?>[] handlers) {
        if (handlers.length == 0 || !RECEIVE.available) return null;
        try {
            var chain = new ReceiveChain[(handlers.length + WIDTH - 1) / WIDTH];
            for (int position = 0; position < chain.length; position++) {
                int from = position * WIDTH;
                int count = Math.min(WIDTH, handlers.length - from);
                chain[position] = (ReceiveChain) RECEIVE.constructor(handlers, from, count, position)
                        .invokeExact((PacketReceiveHandler[]) handlers, from, count);
            }
            return chain;
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    /** Segments calling {@code handlers} in order when run in order, or null when no clone can be defined. */
    static SendChain[] send(PacketSendHandler<?>[] handlers) {
        if (handlers.length == 0 || !SEND.available) return null;
        try {
            var chain = new SendChain[(handlers.length + WIDTH - 1) / WIDTH];
            for (int position = 0; position < chain.length; position++) {
                int from = position * WIDTH;
                int count = Math.min(WIDTH, handlers.length - from);
                chain[position] = (SendChain) SEND.constructor(handlers, from, count, position)
                        .invokeExact((PacketSendHandler[]) handlers, from, count);
            }
            return chain;
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    /** The handler a segment of {@code count} handlers from {@code from} calls at {@code site}, or null. */
    static <H> H slot(H[] handlers, int from, int count, int site) {
        int index = site - (WIDTH - count);
        return index < 0 ? null : handlers[from + index];
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
            for (int i = from; i < from + count; i++)
                builder.append(',').append(handlers[i].getClass().getName());
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
