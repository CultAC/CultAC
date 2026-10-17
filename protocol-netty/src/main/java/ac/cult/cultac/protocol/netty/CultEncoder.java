package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.UnsupportedOnVersionException;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.PromiseCombiner;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Prepares and emits ordered packet groups synchronously on the connection event loop. */
public final class CultEncoder extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "cult-encoder";
    private static final String HOST_CONTROL_NAME = NAME + "-host-control";

    private final CultConnection connection;
    private boolean insideBundle;
    private Output emission;
    private boolean removed;

    public CultEncoder(CultConnection connection) {
        this.connection = connection;
    }

    public static void install(CultConnection connection) {
        var pipeline = connection.channel().pipeline();
        String before = pipeline.get("encoder") != null ? "encoder" : "outbound_config";
        pipeline.addBefore(
                connection.owner() == connection.channel().eventLoop() ? null : connection.owner(),
                before,
                NAME,
                new CultEncoder(connection));
        if (connection.owner() != connection.channel().eventLoop()) {
            pipeline.addAfter(NAME, HOST_CONTROL_NAME, new HostControl(pipeline.context(NAME)));
        }
    }

    public static void uninstall(Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(HOST_CONTROL_NAME) != null) pipeline.remove(HOST_CONTROL_NAME);
        if (pipeline.get(NAME) != null) pipeline.remove(NAME);
    }

    /** The index after the packet ID VarInt, which {@link Wire#peekVarInt} has read in full. */
    private static int afterPacketId(ByteBuf frame) {
        int index = frame.readerIndex();
        while ((frame.getByte(index++) & 0x80) != 0) {}
        return index;
    }

    private static boolean packetMessage(Object message) {
        return message instanceof ByteBuf
                || message instanceof CultWrite
                || message instanceof CultConnection.WriteGroup
                || message instanceof CultConnection.WriteTask;
    }

    /** Paper waits synchronously for protocol tasks on its I/O loop. Only packets may change executor. */
    private static final class HostControl extends ChannelOutboundHandlerAdapter {
        private final ChannelHandlerContext encoder;

        private HostControl(ChannelHandlerContext encoder) {
            this.encoder = encoder;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (packetMessage(message)) ctx.write(message, promise);
            else encoder.write(message, promise);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        // Host configuration objects pass through to their original handlers.
        if (!packetMessage(message)) {
            ctx.write(message, promise);
            return;
        }
        if (!connection.packets().translated() && message instanceof ByteBuf frame) {
            try {
                var phase = connection.phase(PacketDirection.CLIENTBOUND);
                var route = connection.dispatcher().get(PacketDirection.CLIENTBOUND, phase, Wire.peekVarInt(frame));
                if (route == null || route.type().codec().forwardsUndecoded(frame, afterPacketId(frame))) {
                    if (connection.packetLoggingActive()) {
                        connection.runInModel(
                                () -> connection.observePacket(PacketDirection.CLIENTBOUND, phase, frame));
                    }
                    ctx.write(frame, promise);
                    return;
                }
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("outbound packet lookup", failure);
                ctx.write(frame, promise);
                return;
            }
        }
        boolean[] consumed = {false};
        try {
            connection.runInModel(() -> {
                consumed[0] = true;
                Output output = null;
                try {
                    if (removed || !ctx.channel().isActive()) {
                        new Pending(message, promise).discard(new ClosedChannelException());
                        return;
                    }
                    output = process(ctx, new Pending(message, promise));
                    if (emission == null) emit(ctx, output);
                    else emission.append(output);
                } catch (Throwable failure) {
                    if (output != null) output.discard(failure);
                    promise.tryFailure(failure);
                    CultNetworkManager.reportFailure("outbound packet processing", failure);
                }
            });
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("outbound model context", failure);
            if (!consumed[0]) {
                if (message instanceof ByteBuf frame) ctx.write(frame, promise);
                else new Pending(message, promise).discard(failure);
            }
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (emission == null) ctx.flush();
        else emission.addLast(new Frame(null, null, null));
    }

    /** A host callback runs after all preceding synchronous writes and their proofs. */
    public void executeAfterWrites(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.handler() != this || !ctx.executor().inEventLoop())
            throw new IllegalStateException("Outbound callbacks require this channel's event loop");
        write(ctx, new CultConnection.WriteTask(java.util.Objects.requireNonNull(task)), ctx.newPromise());
    }

    private Output process(ChannelHandlerContext ctx, Pending pending) {
        var output = new Output();
        var activeFamilies = new Families();
        // Recursive callbacks finish first and append their complete groups here.
        // The enclosing group's frames have not been appended yet, so these writes
        // precede even its opening delimiter. No retained expansion tree is needed.
        connection.reentrantWriter(
                (message, promise) -> prepare(ctx, new Pending(message, promise), activeFamilies, output));
        try {
            prepare(ctx, pending, activeFamilies, output);
            return output;
        } catch (Throwable failure) {
            output.discard(failure);
            throw failure;
        } finally {
            connection.reentrantWriter(null);
        }
    }

    private void prepare(ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Output output) {
        // Reentrant writes observe the emitted wire state, not an enclosing
        // group's planned delimiters. I/O cannot emit while this dispatch runs.
        var batch = new Batch(insideBundle);
        ByteBuf fallback = pending.message instanceof ByteBuf frame ? frame.retain() : null;
        try {
            if (pending.message instanceof CultConnection.WriteTask task) {
                // The active writer captures callback-generated packets at this exact
                // queue position, before packets that arrived after the callback.
                task.action().run();
                output.flush |= !output.isEmpty();
                pending.promise.trySuccess();
            } else if (pending.message instanceof CultConnection.WriteGroup group) {
                prepareGroup(ctx, group, pending.promise, activeFamilies, batch);
            } else {
                boolean requested = expandPacket(ctx, pending, activeFamilies, batch);
                if (!insideBundle && (requested || batch.output.size() > 1) && connection.clientHasLevel())
                    wrap(ctx, batch.output);
            }
            output.append(batch.output);
        } catch (Throwable failure) {
            batch.output.discard(failure, fallback == null ? null : pending.promise);
            CultNetworkManager.reportFailure("outbound packet expansion", failure);
            if (fallback != null) {
                PacketType<?> type = fallbackType(fallback);
                output.addLast(new Frame(fallback, pending.promise, type));
                fallback = null;
                connection.placedClientbound(type);
            } else pending.promise.tryFailure(failure);
        } finally {
            if (fallback != null) fallback.release();
        }
    }

    private PacketType<?> fallbackType(ByteBuf frame) {
        try {
            var route = connection
                    .dispatcher()
                    .get(
                            PacketDirection.CLIENTBOUND,
                            connection.phase(PacketDirection.CLIENTBOUND),
                            Wire.peekVarInt(frame));
            return route == null ? null : route.type();
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("outbound fallback identity", failure);
            return null;
        }
    }

    private void prepareGroup(
            ChannelHandlerContext ctx,
            CultConnection.WriteGroup group,
            ChannelPromise promise,
            Families activeFamilies,
            Batch batch) {
        boolean wrapGroup = group.bundle() && !batch.inside && connection.clientHasLevel();
        var parts = new LinkedHashSet<ChannelPromise>();
        if (wrapGroup) batch.add(delimiter(ctx));
        for (CultWrite write : group.writes()) {
            var part = ctx.newPromise();
            parts.add(part);
            var pending = new Pending(write, part);
            if (group.bundle()) {
                expandPacket(ctx, pending, activeFamilies, batch);
            } else {
                // Unbundled groups give each entry its own automatic bundle scope.
                var entry = new Batch(batch.inside);
                try {
                    boolean requested = expandPacket(ctx, pending, activeFamilies, entry);
                    if (!batch.inside && (requested || entry.output.size() > 1) && connection.clientHasLevel())
                        wrap(ctx, entry.output);
                    batch.inside = entry.inside;
                    batch.output.append(entry.output);
                } catch (Throwable failure) {
                    entry.output.discard(failure);
                    throw failure;
                }
            }
        }
        if (wrapGroup) batch.add(delimiter(ctx));
        batch.output.collectPromises(parts);
        batch.output.addGroup(new GroupCompletion(List.copyOf(parts), promise));
    }

    /** Expands only packets. The caller decides whether this expansion needs a bundle. */
    private boolean expandPacket(ChannelHandlerContext ctx, Pending pending, Families activeFamilies, Batch batch) {
        if (!(pending.message instanceof CultWrite write)) {
            return expandFrame(ctx, (ByteBuf) pending.message, pending.promise, false, false, activeFamilies, batch);
        }
        var phase = connection.phase(PacketDirection.CLIENTBOUND);
        ac.cult.cultac.protocol.ProjectedPackets.Encoded encoded;
        try {
            var type = connection.packets().writableType(write.packet());
            if (type.direction() != PacketDirection.CLIENTBOUND) {
                throw new UnsupportedOnVersionException("Not clientbound: " + type);
            }
            // CultAC's own play packets need the client's level; without one they are dropped, never sent.
            if (!type.phases().contains(ConnectionPhase.CONFIGURATION) && !connection.clientHasLevel()) {
                connection.droppedWithoutLevel(type);
                pending.promise.tryFailure(new UnsupportedOnVersionException(type + " needs the client's level"));
                return false;
            }
            encoded = connection.packets().encode(phase, type, write.packet(), ctx.alloc(), connection);
        } catch (Throwable failure) {
            pending.promise.tryFailure(failure);
            CultNetworkManager.reportFailure("authored packet encoding", failure);
            return false;
        }
        if (encoded.frames().isEmpty()) {
            pending.promise.trySuccess();
            return false;
        }
        var parts = encoded.frames().size() > 1
                ? new ArrayList<ChannelPromise>(encoded.frames().size())
                : null;
        boolean requested = false;
        int transferred = 0;
        try {
            for (ByteBuf frame : encoded.frames()) {
                var part = encoded.frames().size() == 1 ? pending.promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                transferred++;
                requested |= expandFrame(ctx, frame, part, write.silent(), true, activeFamilies, batch);
            }
            if (encoded.frames().size() > 1)
                batch.output.addGroup(new GroupCompletion(List.copyOf(parts), pending.promise));
            return requested;
        } catch (Throwable failure) {
            for (int i = transferred; i < encoded.frames().size(); i++)
                encoded.frames().get(i).release();
            pending.promise.tryFailure(failure);
            throw failure;
        }
    }

    /** Owns one physical frame; model-derived values cannot replace its network bytes. */
    @SuppressWarnings("unchecked")
    private boolean expandFrame(
            ChannelHandlerContext ctx,
            ByteBuf bytes,
            ChannelPromise promise,
            boolean silent,
            boolean authored,
            Families activeFamilies,
            Batch batch) {
        var phase = connection.phase(PacketDirection.CLIENTBOUND);
        boolean requested = false;
        PacketType<?>[] untracked = {null};
        PacketType<?> forwardedType = null;
        try {
            var values = connection.packets().read(phase, PacketDirection.CLIENTBOUND, bytes, connection, type -> {
                if (connection.dispatcher().get(type) == null) return false;
                if (phase != ConnectionPhase.PLAY || tracked()) return true;
                untracked[0] = type;
                return false;
            });
            if (untracked[0] != null)
                forwardedType = connection.dispatcher().get(untracked[0]).type();
            for (var value : values) {
                var route = connection.dispatcher().get(value.type());
                forwardedType = route.type();
                connection.prepare();
                if (silent || route.send() == null || activeFamilies.contains(route.type())) continue;
                activeFamilies.addLast(route.type());
                try {
                    var type = (PacketType<ClientboundPacket>) route.type();
                    var event = new PacketSendEvent<>(
                            connection.user(), phase, type, (ClientboundPacket) value.packet(), batch.inside);
                    if (authored) event.authored();
                    connection.dispatcher().send(event, route.send());
                    if (event.isCancelled()) {
                        promise.trySuccess();
                        return false;
                    }
                    for (CultWrite write : event.writesBefore()) {
                        expandPacket(ctx, new Pending(write, ctx.newPromise()), activeFamilies, batch);
                    }
                    {
                        if (bytes == null) throw new IllegalStateException("Multiple physical packet identities");
                        if (event.isReplaced()) {
                            var replacement = connection
                                    .packets()
                                    .encode(phase, type, event.getPacket(), ctx.alloc(), connection);
                            bytes.release();
                            bytes = null;
                            appendReplacement(ctx, replacement, promise, type, batch);
                        } else {
                            batch.add(new Frame(bytes, promise, type));
                            bytes = null;
                        }
                        connection.placedClientbound(type);
                    }
                    for (CultWrite write : event.writesAfter()) {
                        expandPacket(ctx, new Pending(write, ctx.newPromise()), activeFamilies, batch);
                    }
                    batch.output.addTasks(event.tasksAfter());
                    requested |= event.isBundleRequested();
                } finally {
                    activeFamilies.removeLast();
                }
            }
            // Unknown/cancelled model packets still retain their physical representation.
            if (bytes != null) {
                PacketType<?> placed = forwardedType != null ? forwardedType : fallbackType(bytes);
                batch.add(new Frame(bytes, promise, forwardedType));
                bytes = null;
                connection.placedClientbound(placed);
            }
            return requested;
        } finally {
            if (bytes != null) bytes.release();
        }
    }

    /**
     * Send routes dispatch only to an attached player, so an untracked connection's play values
     * would be decoded and discarded; its frames still forward unchanged. The player's model
     * supplies older-wire world values, so decoding them without one cannot succeed.
     */
    private boolean tracked() {
        connection.prepare();
        return connection.player() != null;
    }

    private void appendReplacement(
            ChannelHandlerContext ctx,
            ac.cult.cultac.protocol.ProjectedPackets.Encoded replacement,
            ChannelPromise promise,
            PacketType<?> type,
            Batch batch) {
        if (replacement.frames().isEmpty()) {
            promise.trySuccess();
            return;
        }
        var parts = replacement.frames().size() > 1
                ? new ArrayList<ChannelPromise>(replacement.frames().size())
                : null;
        int transferred = 0;
        try {
            for (ByteBuf frame : replacement.frames()) {
                var part = replacement.frames().size() == 1 ? promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                batch.add(new Frame(frame, part, transferred == 0 ? type : null));
                transferred++;
            }
            if (replacement.frames().size() > 1)
                batch.output.addGroup(new GroupCompletion(List.copyOf(parts), promise));
        } catch (Throwable failure) {
            for (int i = transferred; i < replacement.frames().size(); i++)
                replacement.frames().get(i).release();
            throw failure;
        }
    }

    private void wrap(ChannelHandlerContext ctx, Output output) {
        output.addFirst(delimiter(ctx));
        output.addLast(delimiter(ctx));
    }

    private Frame delimiter(ChannelHandlerContext ctx) {
        var type = ClientboundPackets.BUNDLE_DELIMITER;
        var encoded = connection
                .packets()
                .encode(
                        connection.phase(PacketDirection.CLIENTBOUND),
                        type,
                        type.opaqueValue(),
                        ctx.alloc(),
                        connection);
        if (encoded.frames().size() != 1) {
            encoded.frames().forEach(ByteBuf::release);
            throw new IllegalStateException("Bundle delimiter must encode one physical frame");
        }
        ByteBuf bytes = encoded.frames().getFirst();
        return new Frame(bytes, ctx.newPromise(), type);
    }

    private void emit(ChannelHandlerContext ctx, Output output) {
        if (removed || !ctx.channel().isActive()) {
            output.discard(new ClosedChannelException());
            return;
        }
        emission = output;
        try {
            do {
                settleGroups(ctx, output);
                while (!output.isEmpty()) {
                    settleGroups(ctx, output);
                    Frame frame = output.removeFirst();
                    if (frame.bytes == null) {
                        ctx.flush();
                        continue;
                    }
                    connection.observePacket(
                            PacketDirection.CLIENTBOUND, connection.phase(PacketDirection.CLIENTBOUND), frame.bytes);
                    ctx.write(frame.bytes, frame.promise);
                    if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) insideBundle = !insideBundle;
                    else if (frame.type != null) {
                        try {
                            connection.forwarded(frame.type, null);
                        } catch (Throwable failure) {
                            CultNetworkManager.reportFailure("outbound protocol transition", failure);
                        }
                    }
                }
                if (output.flush) {
                    output.flush = false;
                    ctx.flush();
                }
                if (!output.isEmpty()) continue;
                while (output.hasTasks() && output.isEmpty())
                    CultNetworkManager.runDeferredPacketTask("tasksAfterSend", output.tasks.removeFirst());
            } while (!output.isEmpty() || output.hasTasks() || output.flush);
        } finally {
            emission = null;
        }
    }

    private void settleGroups(ChannelHandlerContext ctx, Output output) {
        if (output.groups == null) return;
        for (GroupCompletion group : output.groups) {
            var combiner = new PromiseCombiner(ctx.executor());
            for (ChannelPromise part : group.parts) combiner.add((Future<?>) part);
            combiner.finish(group.promise);
        }
        output.groups.clear();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        removed = true;
    }

    private record Pending(Object message, ChannelPromise promise) {
        void discard(Throwable failure) {
            if (message instanceof ByteBuf bytes) bytes.release();
            promise.tryFailure(failure);
        }
    }

    private record Frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type) {}

    private record GroupCompletion(List<ChannelPromise> parts, ChannelPromise promise) {}

    /** Inline recursion guard for the ordinary one-family dispatch. */
    private static final class Families {
        PacketType<?> first;
        ArrayDeque<PacketType<?>> nested;

        boolean contains(PacketType<?> type) {
            return first == type || nested != null && nested.contains(type);
        }

        void addLast(PacketType<?> type) {
            if (first == null) first = type;
            else {
                if (nested == null) nested = new ArrayDeque<>(2);
                nested.addLast(type);
            }
        }

        void removeLast() {
            if (nested == null || nested.isEmpty()) first = null;
            else nested.removeLast();
        }
    }

    /** Local bundle cursor during expansion; it never changes the emitted wire state. */
    private static final class Batch {
        final Output output = new Output();
        boolean inside;

        Batch(boolean inside) {
            this.inside = inside;
        }

        void add(Frame frame) {
            output.addLast(frame);
            if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) inside = !inside;
        }
    }

    /** Owns only un-emitted frames. Appending transfers ownership without retaining a tree. */
    private static final class Output {
        // Most expansions contain exactly one frame. Allocate a queue only for a second one.
        Frame first;
        ArrayDeque<Frame> tail;

        boolean isEmpty() {
            return first == null;
        }

        int size() {
            return first == null ? 0 : 1 + (tail == null ? 0 : tail.size());
        }

        void addLast(Frame frame) {
            if (first == null) first = frame;
            else {
                if (tail == null) tail = new ArrayDeque<>(4);
                tail.addLast(frame);
            }
        }

        void addFirst(Frame frame) {
            if (first != null) {
                if (tail == null) tail = new ArrayDeque<>(4);
                tail.addFirst(first);
            }
            first = frame;
        }

        Frame removeFirst() {
            Frame result = first;
            first = tail == null ? null : tail.pollFirst();
            return result;
        }

        void collectPromises(java.util.Set<ChannelPromise> parts) {
            if (first != null) parts.add(first.promise);
            if (tail != null) for (Frame frame : tail) parts.add(frame.promise);
        }

        ArrayDeque<Runnable> tasks;
        List<GroupCompletion> groups;
        boolean flush;

        boolean hasTasks() {
            return tasks != null && !tasks.isEmpty();
        }

        void addTasks(List<Runnable> actions) {
            if (actions.isEmpty()) return;
            if (tasks == null) tasks = new ArrayDeque<>(actions.size());
            tasks.addAll(actions);
        }

        void addGroup(GroupCompletion group) {
            if (groups == null) groups = new ArrayList<>(1);
            groups.add(group);
        }

        void append(Output source) {
            if (!source.isEmpty()) {
                if (isEmpty()) {
                    first = source.first;
                    tail = source.tail;
                    source.first = null;
                    source.tail = null;
                } else while (!source.isEmpty()) addLast(source.removeFirst());
            }
            if (source.hasTasks()) {
                if (tasks == null) tasks = source.tasks;
                else tasks.addAll(source.tasks);
            }
            if (source.groups != null && !source.groups.isEmpty()) {
                if (groups == null) groups = source.groups;
                else for (int i = 0; i < source.groups.size(); i++) groups.add(source.groups.get(i));
            }
            flush |= source.flush;
            source.tasks = null;
            source.groups = null;
            source.flush = false;
        }

        void discard(Throwable failure) {
            discard(failure, null);
        }

        void discard(Throwable failure, ChannelPromise preserved) {
            while (!isEmpty()) {
                Frame frame = removeFirst();
                if (frame.bytes != null) {
                    frame.bytes.release();
                    if (frame.promise != preserved) frame.promise.tryFailure(failure);
                }
            }
            if (groups != null) {
                for (GroupCompletion group : groups) {
                    if (group.promise != preserved) group.promise.tryFailure(failure);
                }
                groups.clear();
            }
            if (tasks != null) tasks.clear();
        }
    }
}
