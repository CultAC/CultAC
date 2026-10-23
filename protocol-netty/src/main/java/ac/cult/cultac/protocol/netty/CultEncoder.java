package ac.cult.cultac.protocol.netty;

import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultNetworkManager;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.PacketDispatcher;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionLifecycle;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketBundlePolicy;
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
    private final RouteProbe routeProbe = new RouteProbe();
    private final ReentrantWrites reentrantWrites = new ReentrantWrites();
    private boolean insideBundle;
    // Members of the current physical flush bundle. BundlerInfo accepts at most 4,096.
    private int flushBundleMembers;
    // The latest CultAC ping written into the current physical flush bundle; 0 when there is none.
    private int flushPing;
    private static final int MAX_BUNDLE_PACKETS = 4096;
    // A packet shares the bundle's ping only while the frames prepared around it still fit.
    private static final int SHARE_HEADROOM = 16;
    private Output emission;
    // The latest CultAC ping that every frame written since shares a client bundle with; 0 when there is none.
    private int provingPing;
    // Counts opened flush bundles, so a queued close ends only the bundle its flush saw.
    private int flushBundles;
    // The flush bundle whose close is queued; a newer bundle queues its own.
    private int closeQueuedFor = -1;
    // The flush bundle holds a host bundle whose closing delimiter it has not reached.
    private boolean heldHostBundle;
    // Delimiter frames retain their preparation generation across a protocol-changing terminal.
    private int protocolGeneration;
    // Only the known closing marker of this retired source scope may be consumed; never a new-phase opener.
    private int retiredBundleGeneration = -1;
    // The output of the outermost write being prepared; its frames precede the packet being expanded.
    private Output processing;
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
        if (emission == null
                && !connection.isPreparingWrites()
                && !connection.packets().translated()
                && message instanceof ByteBuf frame
                && !PacketBundlePolicy.requiresStandalone(wireType(frame))) {
            try {
                var phase = connection.phase(PacketDirection.CLIENTBOUND);
                var route = connection.dispatcher().get(PacketDirection.CLIENTBOUND, phase, Wire.peekVarInt(frame));
                if (route == null || route.type().codec().forwardsUndecoded(frame, afterPacketId(frame))) {
                    if (connection.packetLoggingActive()) {
                        connection.runInModel(
                                () -> connection.observePacket(PacketDirection.CLIENTBOUND, phase, frame));
                    }
                    writeRaw(ctx, frame, promise);
                    return;
                }
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("outbound packet lookup", failure);
                writeRaw(ctx, frame, promise);
                return;
            }
        }
        if (emission == null
                && !connection.isPreparingWrites()
                && message instanceof ByteBuf frame
                && !PacketBundlePolicy.requiresStandalone(wireType(frame))
                && unroutedTranslated(frame)) {
            if (removed || !ctx.channel().isActive()) {
                new Pending(frame, promise).discard(new ClosedChannelException());
            } else {
                connection.observePacket(
                        PacketDirection.CLIENTBOUND, connection.phase(PacketDirection.CLIENTBOUND), frame);
                writeRaw(ctx, frame, promise);
            }
            return;
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
                    output = process(ctx, message, promise);
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
                if (message instanceof ByteBuf frame) writeRaw(ctx, frame, promise);
                else new Pending(message, promise).discard(failure);
            }
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (emission == null) {
            flushBundle(ctx);
        } else {
            // A recursive flush happens after the frames already in emission.
            emission.addLast(frame(null, null, null));
        }
    }

    /** A host callback runs after all preceding synchronous writes and their proofs. */
    public void executeAfterWrites(ChannelHandlerContext ctx, Runnable task) {
        if (ctx.handler() != this || !ctx.executor().inEventLoop())
            throw new IllegalStateException("Outbound callbacks require this channel's event loop");
        write(ctx, new CultConnection.WriteTask(java.util.Objects.requireNonNull(task)), ctx.newPromise());
    }

    private Output process(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        var output = new Output();
        Output enclosing = processing;
        processing = output;
        var activeFamilies = new Families();
        // Recursive callbacks finish first and append their complete groups here.
        // The enclosing group's frames have not been appended yet, so these writes
        // precede even its opening delimiter. No retained expansion tree is needed.
        var enclosingCtx = reentrantWrites.ctx;
        var enclosingFamilies = reentrantWrites.activeFamilies;
        var enclosingOutput = reentrantWrites.output;
        reentrantWrites.ctx = ctx;
        reentrantWrites.activeFamilies = activeFamilies;
        reentrantWrites.output = output;
        connection.reentrantWriter(reentrantWrites);
        connection.flushPingSource(this::provingPing);
        try {
            prepare(ctx, message, promise, activeFamilies, output);
            return output;
        } catch (Throwable failure) {
            output.discard(failure);
            throw failure;
        } finally {
            connection.reentrantWriter(null);
            connection.flushPingSource(null);
            processing = enclosing;
            reentrantWrites.ctx = enclosingCtx;
            reentrantWrites.activeFamilies = enclosingFamilies;
            reentrantWrites.output = enclosingOutput;
        }
    }

    private void prepare(
            ChannelHandlerContext ctx, Object message, ChannelPromise promise, Families activeFamilies, Output output) {
        // Reentrant writes observe the emitted wire state, not an enclosing
        // group's planned delimiters. I/O cannot emit while this dispatch runs.
        var batch = new Batch(insideBundle);
        ByteBuf fallback = message instanceof ByteBuf frame ? frame.retain() : null;
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
                // A leading ping opens the flush bundle at emission, which then holds this packet.
                if (!insideBundle && !batch.lead && (requested || batch.size() > 1) && connection.clientHasLevel())
                    wrap(ctx, batch);
            }
            output.append(batch);
        } catch (Throwable failure) {
            batch.discard(failure, fallback == null ? null : promise);
            CultNetworkManager.reportFailure("outbound packet expansion", failure);
            if (fallback != null) {
                PacketType<?> type = fallbackType(fallback);
                output.addLast(frame(fallback, promise, type));
                fallback = null;
                connection.placedClientbound(type);
            } else promise.tryFailure(failure);
        } finally {
            if (fallback != null) fallback.release();
        }
    }

    /** A translated frame whose type has no route, which the expansion would emit alone and unchanged. */
    private boolean unroutedTranslated(ByteBuf frame) {
        try {
            var packets = connection.packets();
            if (!packets.translated() || packets.closed()) return false;
            var type = packets.type(
                    connection.phase(PacketDirection.CLIENTBOUND), PacketDirection.CLIENTBOUND, Wire.peekVarInt(frame));
            return type == null || connection.dispatcher().get(type) == null;
        } catch (Throwable failure) {
            return false;
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
                    if (!batch.inside && !entry.lead && (requested || entry.size() > 1) && connection.clientHasLevel())
                        wrap(ctx, entry);
                    batch.inside = entry.inside;
                    batch.lead |= entry.lead;
                    batch.append(entry);
                } catch (Throwable failure) {
                    entry.discard(failure);
                    throw failure;
                }
            }
        }
        if (wrapGroup) batch.add(delimiter(ctx));
        batch.collectPromises(parts);
        batch.addGroup(new GroupCompletion(List.copyOf(parts), promise));
    }

    private boolean expandPacket(
            ChannelHandlerContext ctx, Object message, ChannelPromise promise, Families activeFamilies, Batch batch) {
        return expandPacket(ctx, message, promise, activeFamilies, batch, 0);
    }

    /**
     * Expands only packets. The caller decides whether this expansion needs a bundle. A nonzero {@code leadPing}
     * is the transaction of the CultAC ping in {@code message}, which opens the flush bundle.
     */
    private boolean expandPacket(
            ChannelHandlerContext ctx,
            Object message,
            ChannelPromise promise,
            Families activeFamilies,
            Batch batch,
            int leadPing) {
        if (!(message instanceof CultWrite write)) {
            return expandFrame(ctx, (ByteBuf) message, promise, false, false, activeFamilies, batch, 0);
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
            for (int i = 0; i < encoded.frames().size(); i++) {
                ByteBuf frame = encoded.frames().get(i);
                var part = encoded.frames().size() == 1 ? promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                transferred++;
                requested |= expandFrame(ctx, frame, part, write.silent(), true, activeFamilies, batch, leadPing);
            }
            if (encoded.frames().size() > 1) batch.addGroup(new GroupCompletion(List.copyOf(parts), promise));
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
            Batch batch,
            int leadPing) {
        var phase = connection.phase(PacketDirection.CLIENTBOUND);
        boolean requested = false;
        PacketType<?> forwardedType = null;
        try {
            routeProbe.phase = phase;
            routeProbe.found = null;
            routeProbe.skipped = null;
            var value = connection.packets().readOne(phase, PacketDirection.CLIENTBOUND, bytes, connection, routeProbe);
            var route = routeProbe.found;
            if (routeProbe.skipped != null) forwardedType = routeProbe.skipped.type();
            if (value != null) {
                forwardedType = route.type();
                connection.prepare();
            }
            if (value != null && !silent && route.send() != null && !activeFamilies.contains(route.type())) {
                activeFamilies.addLast(route.type());
                try {
                    var type = (PacketType<ClientboundPacket>) route.type();
                    var event = new PacketSendEvent<>(
                            connection.user(), phase, type, (ClientboundPacket) value, batch.inside);
                    if (authored) event.authored();
                    // Emission closes the flush bundle before a structural packet, so its ping cannot prove one.
                    event.sharedPing(PacketBundlePolicy.requiresStandalone(type) ? 0 : sharedPing(batch));
                    connection.dispatcher().send(event, route.send());
                    if (event.isCancelled()) {
                        promise.trySuccess();
                        return false;
                    }
                    CultWrite leading = event.leadingPing();
                    if (leading != null) {
                        expandPacket(ctx, leading, ctx.newPromise(), activeFamilies, batch, event.flushPing());
                    }
                    var writesBefore = event.writesBefore();
                    if (!writesBefore.isEmpty()) {
                        for (CultWrite write : writesBefore) {
                            expandPacket(ctx, write, ctx.newPromise(), activeFamilies, batch);
                        }
                    }
                    {
                        if (bytes == null) throw new IllegalStateException("Multiple physical packet identities");
                        if (event.isReplaced()) {
                            var replacement = connection
                                    .packets()
                                    .encode(phase, type, event.getPacket(), ctx.alloc(), connection);
                            bytes.release();
                            bytes = null;
                            appendReplacement(ctx, replacement, promise, type, batch, leadPing);
                        } else {
                            int ping = leadPing != 0 ? leadPing : event.trackedPing();
                            batch.add(frame(bytes, promise, type, ping, leadPing != 0));
                            bytes = null;
                        }
                        connection.placedClientbound(type);
                    }
                    var writesAfter = event.writesAfter();
                    if (!writesAfter.isEmpty()) {
                        for (CultWrite write : writesAfter) {
                            expandPacket(ctx, write, ctx.newPromise(), activeFamilies, batch);
                        }
                    }
                    batch.addTasks(event.tasksAfter());
                    requested |= event.isBundleRequested();
                } finally {
                    activeFamilies.removeLast();
                }
            }
            // Unknown/cancelled model packets still retain their physical representation.
            if (bytes != null) {
                PacketType<?> placed = forwardedType != null ? forwardedType : fallbackType(bytes);
                batch.add(frame(bytes, promise, forwardedType, leadPing, leadPing != 0));
                bytes = null;
                connection.placedClientbound(placed);
            }
            return requested;
        } finally {
            if (bytes != null) bytes.release();
        }
    }

    /** Reset before each frame's read; the routes are assigned after {@link #tracked()}, which can expand frames. */
    private final class RouteProbe implements Predicate<PacketType<?>> {
        ConnectionPhase phase;
        PacketDispatcher.Route found;
        PacketDispatcher.Route skipped;

        @Override
        public boolean test(PacketType<?> type) {
            var phase = this.phase;
            var route = connection.dispatcher().get(type);
            if (route == null) return false;
            boolean consumed = phase != ConnectionPhase.PLAY
                    || tracked()
                    || route.send() != null && route.send().hasConnectionHandlers();
            found = route;
            skipped = consumed ? null : route;
            return consumed;
        }
    }

    /** Prepares writes made while a write group is processed, into that group's output. */
    private final class ReentrantWrites implements BiConsumer<Object, ChannelPromise> {
        ChannelHandlerContext ctx;
        Families activeFamilies;
        Output output;

        @Override
        public void accept(Object message, ChannelPromise promise) {
            prepare(ctx, message, promise, activeFamilies, output);
        }
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
            Batch batch,
            int leadPing) {
        if (replacement.frames().isEmpty()) {
            promise.trySuccess();
            return;
        }
        var parts = replacement.frames().size() > 1
                ? new ArrayList<ChannelPromise>(replacement.frames().size())
                : null;
        int transferred = 0;
        try {
            for (int i = 0; i < replacement.frames().size(); i++) {
                ByteBuf frame = replacement.frames().get(i);
                var part = replacement.frames().size() == 1 ? promise : ctx.newPromise();
                if (parts != null) parts.add(part);
                boolean first = transferred == 0;
                batch.add(frame(frame, part, first ? type : null, first ? leadPing : 0, first && leadPing != 0));
                transferred++;
            }
            if (replacement.frames().size() > 1) batch.addGroup(new GroupCompletion(List.copyOf(parts), promise));
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
        ByteBuf bytes = connection
                .packets()
                .encodeBundleDelimiter(connection.phase(PacketDirection.CLIENTBOUND), ctx.alloc(), connection);
        return frame(bytes, ctx.newPromise(), type);
    }

    /**
     * The ping a packet prepared now can share. Frames prepared earlier and not yet emitted could close or split
     * the flush bundle before this packet, so with any of them pending the packet leads with its own ping.
     */
    private int sharedPing(Batch batch) {
        return batch.isEmpty() ? sharedPing() : 0;
    }

    /**
     * The CultAC ping whose pong proves the client handled every packet written so far, at the current queue
     * position: the latest one, when each packet since shares its client bundle; 0 when there is none.
     */
    private int provingPing() {
        boolean pendingFrames =
                (emission != null && !emission.isEmpty()) || (processing != null && !processing.isEmpty());
        return pendingFrames ? 0 : provingPing;
    }

    /** As {@link #sharedPing(Batch)}, at the current queue position. */
    private int sharedPing() {
        if (!connection.flushBundleOpen()
                || flushPing == 0
                || flushBundleMembers >= MAX_BUNDLE_PACKETS - SHARE_HEADROOM) return 0;
        boolean pendingFrames =
                (emission != null && !emission.isEmpty()) || (processing != null && !processing.isEmpty());
        return pendingFrames ? 0 : flushPing;
    }

    private void writeFrame(ChannelHandlerContext ctx, Frame frame) {
        connection.observePacket(
                PacketDirection.CLIENTBOUND, connection.phase(PacketDirection.CLIENTBOUND), frame.bytes);
        // Downstream handlers can synchronously write another packet. Publish this frame's wire state first.
        if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) {
            insideBundle = !insideBundle;
            flushBundleMembers = 0;
        }
        // A later ping in the bundle proves the same task, so it replaces the one packets share.
        if (frame.ping != 0 && connection.flushBundleOpen()) flushPing = frame.ping;
        if (frame.ping != 0) provingPing = frame.ping;
        else if (frame.type != ClientboundPackets.BUNDLE_DELIMITER) outrunProvingPing();
        ctx.write(frame.bytes, frame.promise);
        if (frame.type != null && frame.type != ClientboundPackets.BUNDLE_DELIMITER) {
            try {
                var phase = connection.phase(PacketDirection.CLIENTBOUND);
                if (ConnectionLifecycle.nextPhase(PacketDirection.CLIENTBOUND, phase, frame.type, null) != phase)
                    protocolGeneration++;
                connection.forwarded(frame.type, null);
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("outbound protocol transition", failure);
            }
        }
    }

    /** A packet written outside the proving ping's client bundle is handled after its pong. */
    private void outrunProvingPing() {
        if (!connection.flushBundleOpen() || !delimitsFlushBundle()) provingPing = 0;
    }

    /** A frame forwarded without expansion; inside an open flush bundle it is an ordinary member. */
    private void writeRaw(ChannelHandlerContext ctx, ByteBuf frame, ChannelPromise promise) {
        PacketType<?> type = wireType(frame);
        if (PacketBundlePolicy.requiresStandalone(type)) {
            // Even a model-context fallback must serialize the close/write/resume sequence against reentry.
            var output = new Output();
            output.addLast(frame(frame, promise, type));
            if (emission == null) emit(ctx, output);
            else emission.append(output);
            return;
        }
        countFlushBundleMember(ctx);
        ctx.write(frame, promise);
        outrunProvingPing();
    }

    /** The model family of a clientbound frame, whether or not CultAC listens to it; null when unknown. */
    private PacketType<?> wireType(ByteBuf frame) {
        try {
            return connection
                    .packets()
                    .type(
                            connection.phase(PacketDirection.CLIENTBOUND),
                            PacketDirection.CLIENTBOUND,
                            Wire.peekVarInt(frame));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * A leading ping opens the flush bundle, or a new physical bundle when the current one is nearly full. A ping
     * and its packet must share a client bundle, so the lead leaves them the headroom a sharing packet keeps.
     */
    private void openFlushBundle(ChannelHandlerContext ctx) {
        if (connection.flushBundleOpen()) {
            if (flushBundleMembers < MAX_BUNDLE_PACKETS - SHARE_HEADROOM) return;
            writeFrame(ctx, delimiter(ctx));
            writeFrame(ctx, delimiter(ctx));
            provingPing = 0;
        } else {
            if (!connection.clientHasLevel()) return;
            if (delimitsFlushBundle()) {
                // The host's open bundle becomes the flush bundle, which then owes it its closing delimiter.
                if (insideBundle) {
                    heldHostBundle = true;
                    // The host's packets so far stay in their own bundle; the rest continue after the ping.
                    if (flushBundleMembers >= MAX_BUNDLE_PACKETS - SHARE_HEADROOM) {
                        writeFrame(ctx, delimiter(ctx));
                        writeFrame(ctx, delimiter(ctx));
                    }
                } else writeFrame(ctx, delimiter(ctx));
            }
            connection.flushBundleOpen(true);
            flushBundles++;
        }
        flushPing = 0;
    }

    /**
     * Geyser translates a Java bundle into separate Bedrock packets, so a Bedrock player's flush group is tracked
     * without delimiters: its packets share one ping as a Java bundle's do, without the client handling them in one
     * task.
     */
    private boolean delimitsFlushBundle() {
        return connection.bedrockBridge() == null;
    }

    /** Counts the next member, splitting the physical bundle at the client's limit. */
    private void countFlushBundleMember(ChannelHandlerContext ctx) {
        // Only a client bundle has a member limit.
        if (!insideBundle || !delimitsFlushBundle()) return;
        if (flushBundleMembers == MAX_BUNDLE_PACKETS && connection.flushBundleOpen()) {
            // Unreachable unless more than the headroom was prepared ahead of a sharing packet.
            writeFrame(ctx, delimiter(ctx));
            writeFrame(ctx, delimiter(ctx));
            flushPing = 0;
            provingPing = 0;
        }
        ++flushBundleMembers;
    }

    /**
     * Sends what was written and ends the flush bundle once the writes already queued behind this flush have joined
     * it. A host that does not suspend flushing, as during a respawn, flushes each packet; those queued together
     * still share one ping. The client handles nothing in the bundle before its closing delimiter.
     */
    private void flushBundle(ChannelHandlerContext ctx) {
        ctx.flush();
        if (!connection.flushBundleOpen() || closeQueuedFor == flushBundles) return;
        int bundle = flushBundles;
        closeQueuedFor = bundle;
        connection.executeLater(() -> {
            if (removed || !ctx.channel().isActive() || bundle != flushBundles) return;
            closeFlushBundle(ctx);
            ctx.flush();
        });
    }

    private void closeFlushBundle(ChannelHandlerContext ctx) {
        if (!connection.flushBundleOpen()) return;
        // Deferred closure runs outside emit(). Queue downstream reentry until the closing delimiters and
        // any held host continuation have been written, just as closure before a standalone packet does.
        if (emission == null) {
            var output = new Output();
            emission = output;
            try {
                closeFlushBundle(ctx);
            } finally {
                emission = null;
            }
            emit(ctx, output);
            return;
        }
        connection.flushBundleOpen(false);
        flushPing = 0;
        boolean resumeHostBundle = heldHostBundle;
        heldHostBundle = false;
        if (delimitsFlushBundle()) {
            writeFrame(ctx, delimiter(ctx));
            // The rest of a held host bundle stays in a bundle of its own, closed by its own delimiter.
            if (resumeHostBundle) writeFrame(ctx, delimiter(ctx));
        }
    }

    /**
     * Leave the physical stream outside a bundle without moving the standalone packet past earlier frames.
     * A held source/explicit continuation may reopen only after the packet, and only in the same protocol.
     * Its original closing delimiter still owns the resumed bundle. A pure flush bundle needs no continuation:
     * the next packet that needs a proof will lead with a fresh ping.
     */
    private boolean suspendBundle(ChannelHandlerContext ctx) {
        boolean resumeBundle = insideBundle;
        if (connection.flushBundleOpen() && delimitsFlushBundle()) {
            resumeBundle = heldHostBundle;
            heldHostBundle = false;
        }
        closeFlushBundle(ctx);
        // A source/explicit bundle can exist without a flush bundle (including a translated connection).
        if (insideBundle) writeFrame(ctx, delimiter(ctx));
        return resumeBundle;
    }

    /**
     * Inside an open flush bundle a host bundle's delimiters are dropped and its packets join the flush bundle,
     * which ClientPacketListener#handleBundlePacket still handles in one task. A client bundle cannot nest.
     */
    private boolean joinsFlushBundle(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type != ClientboundPackets.BUNDLE_DELIMITER
                || !connection.flushBundleOpen()
                || !delimitsFlushBundle()) return false;
        heldHostBundle = !heldHostBundle;
        frame.bytes.release();
        if (frame.promise != null) frame.promise.trySuccess();
        return true;
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
                        flushBundle(ctx);
                        continue;
                    }
                    if (frame.type == ClientboundPackets.BUNDLE_DELIMITER
                            && frame.generation == retiredBundleGeneration) {
                        // This marker was identified while the old protocol still owned the source bundle.
                        // Raw packet IDs received after a switch are never guessed to be old delimiters.
                        retiredBundleGeneration = -1;
                        frame.bytes.release();
                        frame.promise.trySuccess();
                        continue;
                    }
                    if (joinsFlushBundle(ctx, frame)) continue;
                    // Unlistened authored/translated frames can have no dispatch type; their wire identity
                    // still determines whether they may join a bundle and advance the protocol.
                    PacketType<?> type = frame.type != null ? frame.type : wireType(frame.bytes);
                    boolean standalone = PacketBundlePolicy.requiresStandalone(type);
                    if (standalone && frame.type == null)
                        frame = new Frame(frame.bytes, frame.promise, type, frame.ping, frame.lead, frame.generation);
                    boolean resumeBundle = false;
                    if (standalone) resumeBundle = suspendBundle(ctx);
                    else if (frame.lead) openFlushBundle(ctx);
                    var phase = connection.phase(PacketDirection.CLIENTBOUND);
                    int generation = protocolGeneration;
                    countFlushBundleMember(ctx);
                    writeFrame(ctx, frame);
                    if (resumeBundle) {
                        if (connection.phase(PacketDirection.CLIENTBOUND) == phase) writeFrame(ctx, delimiter(ctx));
                        else retiredBundleGeneration = generation;
                    }
                }
                if (output.flush) {
                    output.flush = false;
                    flushBundle(ctx);
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
        // Uninstall and rebind happen on live connections. A flush bundle left open on the
        // wire would absorb every later packet, so close it first.
        if (ctx.channel().isActive() && connection.flushBundleOpen()) {
            try {
                closeFlushBundle(ctx);
                ctx.flush();
            } catch (Throwable failure) {
                CultNetworkManager.reportFailure("flush bundle close on removal", failure);
            }
        }
        connection.flushBundleOpen(false);
        flushPing = 0;
        heldHostBundle = false;
        retiredBundleGeneration = -1;
    }

    private record Pending(Object message, ChannelPromise promise) {
        void discard(Throwable failure) {
            if (message instanceof ByteBuf bytes) bytes.release();
            promise.tryFailure(failure);
        }
    }

    private Frame frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type) {
        return frame(bytes, promise, type, 0, false);
    }

    private Frame frame(ByteBuf bytes, ChannelPromise promise, PacketType<?> type, int ping, boolean lead) {
        return new Frame(bytes, promise, type, ping, lead, protocolGeneration);
    }

    /** {@code ping} is a CultAC transaction; {@code generation} identifies prepared old-protocol delimiters. */
    private record Frame(
            ByteBuf bytes, ChannelPromise promise, PacketType<?> type, int ping, boolean lead, int generation) {}

    private record GroupCompletion(List<ChannelPromise> parts, ChannelPromise promise) {}

    /** Inline recursion guard for the ordinary one-family dispatch. */
    private static final class Families {
        PacketType<?> first, second;
        ArrayDeque<PacketType<?>> nested;

        boolean contains(PacketType<?> type) {
            return first == type || second == type || nested != null && nested.contains(type);
        }

        void addLast(PacketType<?> type) {
            if (first == null) first = type;
            else if (second == null) second = type;
            else {
                if (nested == null) nested = new ArrayDeque<>(2);
                nested.addLast(type);
            }
        }

        void removeLast() {
            if (nested != null && !nested.isEmpty()) nested.removeLast();
            else if (second != null) second = null;
            else first = null;
        }
    }

    /** Local bundle cursor during expansion; it never changes the emitted wire state. */
    private static final class Batch extends Output {
        boolean inside;
        // A leading ping opens the flush bundle for this expansion, so it needs no bundle of its own.
        boolean lead;

        Batch(boolean inside) {
            this.inside = inside;
        }

        void add(Frame frame) {
            addLast(frame);
            if (frame.type == ClientboundPackets.BUNDLE_DELIMITER) inside = !inside;
            lead |= frame.lead;
        }
    }

    /** Owns only un-emitted frames. Appending transfers ownership without retaining a tree. */
    private static class Output {
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
            for (int i = 0; i < actions.size(); i++) tasks.addLast(actions.get(i));
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
                else while (!source.tasks.isEmpty()) tasks.addLast(source.tasks.pollFirst());
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
