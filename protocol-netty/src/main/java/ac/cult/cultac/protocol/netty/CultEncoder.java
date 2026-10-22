package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketDispatcher;
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
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Prepares and emits ordered packet groups synchronously on the connection event loop. */
public final class CultEncoder extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "cult-encoder";
    private static final String HOST_CONTROL_NAME = NAME + "-host-control";

    private final CultConnection connection;
    private boolean insideBundle;
    private Output emission;
    private boolean removed;

    // Event-loop-confined scratch. The outermost write reuses it; a write nested inside
    // that write's model task, or a nested preparation, allocates its own as before.
    /** Nesting depth of {@link #write} model tasks. */
    private int writeDepth;
    /** Nesting depth of {@link #prepare} within the outermost write. */
    private int prepareDepth;
    /** Whether the innermost write's model task has started. */
    private boolean consumed;

    private ChannelHandlerContext taskContext;
    private Object taskMessage;
    private ChannelPromise taskPromise;
    private final Runnable writeTask = () -> runWrite(taskContext, taskMessage, taskPromise);
    private final Output scratchOutput = new Output();
    private final Families scratchFamilies = new Families();
    private final Batch scratchBatch = new Batch(false);
    private ChannelHandlerContext writerContext;
    private final BiConsumer<Object, ChannelPromise> scratchWriter =
            (message, promise) -> prepare(writerContext, message, promise, scratchFamilies, scratchOutput);

    /** The phase and untracked family of the innermost {@link #expandFrame} read. */
    private ConnectionPhase readPhase;

    private PacketType<?> untracked;
    private final Predicate<PacketType<?>> consumedType = this::consumes;

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
        if (message instanceof ByteBuf frame) {
            if (!connection.packets().translated()) {
                ConnectionPhase phase;
                PacketDispatcher.Route route;
                try {
                    phase = connection.phase(PacketDirection.CLIENTBOUND);
                    route = connection.dispatcher().get(PacketDirection.CLIENTBOUND, phase, Wire.peekVarInt(frame));
                    if (route == null || route.type().codec().forwardsUndecoded(frame, afterPacketId(frame))) {
                        passThrough(ctx, frame, promise, phase);
                        return;
                    }
                } catch (Throwable failure) {
                    CultNetworkManager.reportFailure("outbound packet lookup", failure);
                    ctx.write(frame, promise);
                    return;
                }
                if (bundleDelimiter(ctx, frame, promise, phase, route)) return;
            } else if (translatedFastPath(ctx, frame, promise)) return;
        }
        int depth = ++writeDepth;
        boolean outerConsumed = consumed;
        consumed = false;
        Runnable task;
        if (depth == 1) {
            taskContext = ctx;
            taskMessage = message;
            taskPromise = promise;
            task = writeTask;
        } else task = () -> runWrite(ctx, message, promise);
        try {
            connection.runInModel(task);
        } catch (Throwable failure) {
            CultNetworkManager.reportFailure("outbound model context", failure);
            if (!consumed) {
                if (message instanceof ByteBuf frame) ctx.write(frame, promise);
                else discard(message, promise, failure);
            }
        } finally {
            consumed = outerConsumed;
            writeDepth--;
            if (depth == 1) {
                taskContext = null;
                taskMessage = null;
                taskPromise = null;
            }
        }
    }

    private void runWrite(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        consumed = true;
        Output output = null;
        try {
            if (removed || !ctx.channel().isActive()) {
                discard(message, promise, new ClosedChannelException());
                return;
            }
            output = process(ctx, message, promise);
            if (emission == null) emit(ctx, output);
            else emission.append(output);
        } catch (Throwable failure) {
            if (output != null) output.discard(failure);
            promise.tryFailure(failure);
            CultNetworkManager.reportFailure("outbound packet processing", failure);
        }
    }

    private static void discard(Object message, ChannelPromise promise, Throwable failure) {
        if (message instanceof ByteBuf bytes) bytes.release();
        promise.tryFailure(failure);
    }

    /** A frame no route consumes leaves unchanged. */
    private void passThrough(ChannelHandlerContext ctx, ByteBuf frame, ChannelPromise promise, ConnectionPhase phase) {
        if (connection.packetLoggingActive()) {
            connection.runInModel(() -> connection.observePacket(PacketDirection.CLIENTBOUND, phase, frame));
        }
        ctx.write(frame, promise);
    }

    /**
     * The client's numbering differs from the dispatcher's, so the family comes from the projection. Only a frame
     * the slow path would forward untouched, without reporting anything, takes this path: a known ID with no
     * route, or a plain bundle delimiter. Anything else, including a failed lookup, takes the slow path.
     */
    private boolean translatedFastPath(ChannelHandlerContext ctx, ByteBuf frame, ChannelPromise promise) {
        if (emission != null || removed || !ctx.channel().isActive()) return false;
        ConnectionPhase phase;
        PacketDispatcher.Route route;
        try {
            var packets = connection.packets();
            // Closed values make the slow path report its read failure.
            if (packets.isClosed()) return false;
            phase = connection.phase(PacketDirection.CLIENTBOUND);
            int id = Wire.peekVarInt(frame);
            if (!packets.hasId(phase, PacketDirection.CLIENTBOUND, id)) return false;
            var type = packets.type(phase, PacketDirection.CLIENTBOUND, id);
            if (type == null) route = null;
            else {
                route = connection.dispatcher().get(type);
                // A routed frame is decoded with the observed runtime's own binding.
                if (route != null && connection.runtime().binding(phase, PacketDirection.CLIENTBOUND, id) == null)
                    return false;
            }
        } catch (RuntimeException failure) {
            return false;
        }
        if (route == null) {
            passThrough(ctx, frame, promise, phase);
            return true;
        }
        return bundleDelimiter(ctx, frame, promise, phase, route);
    }

    /**
     * A server bundle delimiter carries no value and has no handlers; the slow path would forward it unchanged and
     * flip the bundle cursor as it is written. Do exactly that without a model task. Delimiters that are not
     * plain, or that arrive while a group is emitting, before the connection is prepared or after its packet values
     * closed, take the slow path.
     */
    private boolean bundleDelimiter(
            ChannelHandlerContext ctx,
            ByteBuf frame,
            ChannelPromise promise,
            ConnectionPhase phase,
            PacketDispatcher.Route route) {
        if (route.type() != ClientboundPackets.BUNDLE_DELIMITER
                || route.send() != null
                || emission != null
                || removed
                || !connection.prepared()
                || connection.packets().isClosed()
                || !ctx.channel().isActive()
                // An empty payload is the only one the delimiter codec decodes without a failure report.
                || afterPacketId(frame) != frame.writerIndex()) return false;
        passThrough(ctx, frame, promise, phase);
        insideBundle = !insideBundle;
        return true;
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

    private Output process(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        // Only the outermost write's model task uses the scratch; nothing is emitting then.
        boolean outermost = writeDepth == 1;
        Output output;
        Families activeFamilies;
        BiConsumer<Object, ChannelPromise> writer;
        if (outermost) {
            output = scratchOutput;
            output.reset();
            activeFamilies = scratchFamilies;
            activeFamilies.reset();
            writerContext = ctx;
            writer = scratchWriter;
        } else {
            var fresh = new Output();
            var families = new Families();
            output = fresh;
            activeFamilies = families;
            writer = (nested, nestedPromise) -> prepare(ctx, nested, nestedPromise, families, fresh);
        }
        // Recursive callbacks finish first and append their complete groups here.
        // The enclosing group's frames have not been appended yet, so these writes
        // precede even its opening delimiter. No retained expansion tree is needed.
        connection.reentrantWriter(writer);
        try {
            prepare(ctx, message, promise, activeFamilies, output);
            return output;
        } catch (Throwable failure) {
            output.discard(failure);
            throw failure;
        } finally {
            connection.reentrantWriter(null);
            if (outermost) writerContext = null;
        }
    }

    private void prepare(
            ChannelHandlerContext ctx,
            Object message,
            ChannelPromise promise,
            Families activeFamilies,
            Output output) {
        // Reentrant writes observe the emitted wire state, not an enclosing
        // group's planned delimiters. I/O cannot emit while this dispatch runs.
        ByteBuf fallback = message instanceof ByteBuf frame ? frame.retain() : null;
        Batch batch;
        if (writeDepth == 1 && prepareDepth == 0) {
            batch = scratchBatch;
            batch.reset(insideBundle);
        } else batch = new Batch(insideBundle);
        prepareDepth++;
        try {
            if (message instanceof CultConnection.WriteTask task) {
                // The active writer captures callback-generated packets at this exact
                // queue position, before packets that arrived after the callback.
                task.action().run();
                output.flush |= !output.isEmpty();
                promise.trySuccess();
            } else if (message instanceof CultConnection.WriteGroup group) {
                prepareGroup(ctx, group, promise, activeFamilies, batch);
            } else {
                boolean requested = expandPacket(ctx, message, promise, activeFamilies, batch);
                if (!insideBundle && (requested || batch.output.size() > 1) && connection.clientHasLevel())
                    wrap(ctx, batch.output);
            }
            output.append(batch.output);
        } catch (Throwable failure) {
            batch.output.discard(failure, fallback == null ? null : promise);
            CultNetworkManager.reportFailure("outbound packet expansion", failure);
            if (fallback != null) {
                PacketType<?> type = fallbackType(fallback);
                output.addLast(new Frame(fallback, promise, type));
                fallback = null;
                connection.placedClientbound(type);
            } else promise.tryFailure(failure);
        } finally {
            prepareDepth--;
            if (fallback != null) fallback.release();
        }
    }

    private PacketType<?> fallbackType(ByteBuf frame) {
        try {
            // The frame's ID is in the client's numbering, which differs from the dispatcher's when translated.
            var type = connection
                    .packets()
                    .type(
                            connection.phase(PacketDirection.CLIENTBOUND),
                            PacketDirection.CLIENTBOUND,
                            Wire.peekVarInt(frame));
            var route = type == null ? null : connection.dispatcher().get(type);
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
            if (group.bundle()) {
                expandPacket(ctx, write, part, activeFamilies, batch);
            } else {
                // Unbundled groups give each entry its own automatic bundle scope.
                var entry = new Batch(batch.inside);
                try {
                    boolean requested = expandPacket(ctx, write, part, activeFamilies, entry);
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
    private boolean expandPacket(
            ChannelHandlerContext ctx, Object message, ChannelPromise promise, Families activeFamilies, Batch batch) {
        if (!(message instanceof CultWrite write)) {
            return expandFrame(ctx, (ByteBuf) message, promise, false, false, activeFamilies, batch);
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
                promise.tryFailure(new UnsupportedOnVersionException(type + " needs the client's level"));
                return false;
            }
            encoded = connection.packets().encode(phase, type, write.packet(), ctx.alloc(), connection);
        } catch (Throwable failure) {
            promise.tryFailure(failure);
            CultNetworkManager.reportFailure("authored packet encoding", failure);
            return false;
        }
        if (encoded.frames().isEmpty()) {
            promise.trySuccess();
            return false;
        }
        var parts = encoded.frames().size() > 1
                ? new ArrayList<ChannelPromise>(encoded.frames().size())
                : null;
        boolean requested = false;
        int transferred = 0;
        try {
            for (ByteBuf frame : encoded.frames()) {
                var part = encoded.frames().size() == 1 ? promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                transferred++;
                requested |= expandFrame(ctx, frame, part, write.silent(), true, activeFamilies, batch);
            }
            if (encoded.frames().size() > 1)
                batch.output.addGroup(new GroupCompletion(List.copyOf(parts), promise));
            return requested;
        } catch (Throwable failure) {
            for (int i = transferred; i < encoded.frames().size(); i++)
                encoded.frames().get(i).release();
            promise.tryFailure(failure);
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
        PacketType<?> forwardedType = null;
        try {
            ac.cult.cultac.protocol.ProjectedPackets.Value value;
            PacketType<?> skipped;
            // The predicate may run nested reads (through the connection initializer); each read
            // restores the fields it found, so this read sees only its own predicate's result.
            ConnectionPhase outerPhase = readPhase;
            PacketType<?> outerUntracked = untracked;
            readPhase = phase;
            untracked = null;
            try {
                value = connection.packets().readOne(phase, PacketDirection.CLIENTBOUND, bytes, connection, consumedType);
                skipped = untracked;
            } finally {
                readPhase = outerPhase;
                untracked = outerUntracked;
            }
            if (skipped != null) forwardedType = connection.dispatcher().get(skipped).type();
            var route = value == null ? null : connection.dispatcher().get(value.type());
            if (value != null) {
                forwardedType = route.type();
                connection.prepare();
            }
            if (value != null && !silent && route.send() != null && !activeFamilies.contains(route.type())) {
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
                        expandPacket(ctx, write, ctx.newPromise(), activeFamilies, batch);
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
                        expandPacket(ctx, write, ctx.newPromise(), activeFamilies, batch);
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

    /** The read predicate: consumes routed families, except untracked play values no handler can see. */
    private boolean consumes(PacketType<?> type) {
        var route = connection.dispatcher().get(type);
        if (route == null) return false;
        if (readPhase != ConnectionPhase.PLAY || tracked()) return true;
        if (route.send() != null && route.send().hasConnectionHandlers()) return true;
        untracked = type;
        return false;
    }

    /**
     * Send routes without connection handlers dispatch only to an attached player, so an untracked
     * connection's play values for them would be decoded and discarded; those frames still forward
     * unchanged. The player's model
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

        void reset() {
            first = null;
            if (nested != null) nested.clear();
        }
    }

    /** Local bundle cursor during expansion; it never changes the emitted wire state. */
    private static final class Batch {
        final Output output = new Output();
        boolean inside;

        Batch(boolean inside) {
            this.inside = inside;
        }

        void reset(boolean inside) {
            output.reset();
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

        /** Readies a drained scratch output; ownership of anything left was already settled. */
        void reset() {
            first = null;
            if (tail != null) tail.clear();
            if (tasks != null) tasks.clear();
            if (groups != null) groups.clear();
            flush = false;
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
