package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.lang.StackWalker.Option;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Routes call exactly what a loop over their handlers calls: same order, arguments, event state and failure. */
class RouteChainEquivalenceTest {
    private static final int[] SIZES = {1, 2, 3, 7, 8, 9, 15, 16, 17, 24, 43, 44, 45, 64};
    private static final PacketHandlerScanner SCANNER =
            new PacketHandlerScanner(TestProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));
    private static final CultPlayer PLAYER = mock(CultPlayer.class);
    private static final MethodType SPUN_TYPE = MethodType.methodType(
            void.class, List.class, int.class, PacketReceiveEvent.class, CultPlayer.class, Object.class);

    @Test
    void receiveRoutesMatchTheLoop() {
        for (int seed = 0; seed < 600; seed++) {
            var random = new Random(seed);
            var trace = new Trace();
            var handlers = receiveHandlers(random, trace, size(random), random.nextBoolean());
            var route = PacketReceiveRoute.of(handlers.toArray(PacketReceiveHandler[]::new));
            var packet = new ServerboundPong(seed);
            boolean cancelled = random.nextInt(4) == 0;
            var expected = trace.run(cancelled, event -> loop(handlers, event, PLAYER, packet), packet);
            var actual = trace.run(cancelled, event -> route.dispatch(event, PLAYER, packet), packet);
            assertEquals(expected, actual, "seed " + seed);
        }
    }

    @Test
    void sendRoutesMatchTheLoopForPlayersAndConnections() {
        for (int seed = 0; seed < 600; seed++) {
            var random = new Random(seed);
            var trace = new Trace();
            var handlers = sendHandlers(random, trace, size(random), random.nextBoolean());
            var connection = new ArrayList<PacketSendHandler<Object>>();
            for (var handler : handlers) if (random.nextInt(3) == 0) connection.add(handler);
            var route = PacketSendRoute.of(
                    handlers.toArray(PacketSendHandler[]::new), connection.toArray(PacketSendHandler[]::new));
            var packet = new ClientboundPing(seed);
            boolean cancelled = random.nextInt(4) == 0;
            assertEquals(
                    trace.runSend(cancelled, event -> loopSend(handlers, event, PLAYER, packet), packet),
                    trace.runSend(cancelled, event -> route.dispatch(event, PLAYER, packet), packet),
                    "seed " + seed);
            assertEquals(
                    trace.runSend(cancelled, event -> loopSend(connection, event, null, packet), packet),
                    trace.runSend(cancelled, event -> route.dispatchConnection(event, packet), packet),
                    "connection seed " + seed);
        }
    }

    /** The early stage stops the packet when it ends cancelled; ordinary and connection stages follow otherwise. */
    @Test
    void stagedReceiveRoutesKeepTheCancellationExit() {
        for (int seed = 0; seed < 300; seed++) {
            var random = new Random(seed);
            var trace = new Trace();
            var early = receiveHandlers(random, trace, size(random) % 12, random.nextBoolean());
            var ordinary = receiveHandlers(random, trace, size(random), random.nextBoolean());
            var connection = receiveHandlers(random, trace, random.nextInt(4), false);
            var staged = new PacketDispatcher.ReceiveRoute<ServerboundPacket>(
                    PacketReceiveRoute.of(early.toArray(PacketReceiveHandler[]::new)),
                    PacketReceiveRoute.of(ordinary.toArray(PacketReceiveHandler[]::new)),
                    PacketReceiveRoute.of(connection.toArray(PacketReceiveHandler[]::new)));
            var packet = new ServerboundPong(seed);
            boolean cancelled = random.nextInt(4) == 0;
            var expected = trace.run(
                    cancelled,
                    event -> {
                        loop(early, event, PLAYER, packet);
                        if (event.isCancelled()) return;
                        loop(ordinary, event, PLAYER, packet);
                        loop(connection, event, PLAYER, packet);
                    },
                    packet);
            assertEquals(expected, trace.run(cancelled, event -> staged.dispatch(event, PLAYER), packet), "" + seed);
        }
    }

    /** Registration, replacement and clearing publish new routes; a dispatch in flight keeps its own handlers. */
    @Test
    void publishedRoutesFollowRegistrationsIncludingDuringDispatch() {
        var dispatcher = new PacketDispatcher(SCANNER.runtime());
        for (int seed = 0; seed < 200; seed++) {
            var random = new Random(seed);
            var trace = new Trace();
            List<PacketReceiveHandler<Object>> model = new ArrayList<>();
            boolean allOwn = true;
            dispatcher.clear();
            for (int step = 0; step < 6; step++) {
                var added = receiveHandlers(random, trace, size(random) % 20, false);
                boolean own = random.nextBoolean();
                switch (random.nextInt(4)) {
                    case 0 -> {
                        dispatcher.replace(builder -> added.forEach(h -> receive(builder, h)));
                        model = new ArrayList<>(added);
                        allOwn = false;
                    }
                    case 1 -> {
                        dispatcher.clear();
                        model = new ArrayList<>();
                        allOwn = true;
                    }
                    default -> {
                        if (own) dispatcher.registerOwn(builder -> added.forEach(h -> receive(builder, h)));
                        else dispatcher.register(builder -> added.forEach(h -> receive(builder, h)));
                        allOwn = (model.isEmpty() || allOwn) && (own || added.isEmpty());
                        model.addAll(added);
                    }
                }
                var route = dispatcher.get(ServerboundPackets.PONG);
                if (model.isEmpty()) {
                    assertTrue(route == null || route.receive() == null);
                    continue;
                }
                assertEquals(allOwn, route.own());
                var packet = new ServerboundPong(step);
                var inFlight = List.copyOf(model);
                // A handler in the middle publishes a new snapshot while the old route is running.
                trace.publish = () -> dispatcher.register(
                        builder -> receive(builder, (event, player, ignored) -> trace.calls.add("late")));
                var expected = trace.run(false, event -> loop(inFlight, event, PLAYER, packet), packet);
                var actual = trace.run(false, event -> route.receive().dispatch(event, PLAYER), packet);
                assertEquals(expected, actual, "seed " + seed + " step " + step);
                trace.publish = null;
                dispatcher.replace(builder -> inFlight.forEach(h -> receive(builder, h)));
                allOwn = false;
            }
        }
    }

    @Test
    void chainsRunHandlersFromHiddenSegmentClonesOnePerPosition() {
        Set<Class<?>> callers = new HashSet<>();
        List<PacketReceiveHandler<Object>> handlers = new ArrayList<>();
        var walker = StackWalker.getInstance(Set.of(Option.SHOW_HIDDEN_FRAMES, Option.RETAIN_CLASS_REFERENCE));
        for (int i = 0; i < 20; i++) {
            handlers.add((event, player, packet) ->
                    callers.add(walker.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                            .filter(type -> type.getName().startsWith(ReceiveSegment.class.getName()))
                            .findFirst()
                            .orElseThrow())));
        }
        PacketReceiveRoute.of(handlers.toArray(PacketReceiveHandler[]::new)).dispatch(null, null, null);
        assertEquals(3, callers.size());
        for (var caller : callers) assertTrue(caller.isHidden(), caller.getName());
    }

    /** Past the clone bound, positions share a clone; the calls stay the same. */
    @Test
    void manyDistinctHandlerClassesStayEquivalent() throws Throwable {
        MethodHandle target = MethodHandles.lookup().findStatic(RouteChainEquivalenceTest.class, "spun", SPUN_TYPE);
        List<String> calls = new ArrayList<>();
        var random = new Random(7);
        for (int route = 0; route < 1300; route++) {
            List<PacketReceiveHandler<Object>> handlers = new ArrayList<>();
            int count = route % 100 == 0 ? 30 : 1 + random.nextInt(3);
            for (int i = 0; i < count; i++) handlers.add(spin(target, calls, route * 100 + i));
            var packet = new ServerboundPong(route);
            loop(handlers, null, PLAYER, packet);
            var expected = List.copyOf(calls);
            calls.clear();
            PacketReceiveRoute.of(handlers.toArray(PacketReceiveHandler[]::new)).dispatch(null, PLAYER, packet);
            assertEquals(expected, calls);
            calls.clear();
        }
    }

    @Test
    void routesDoNotKeepTheirHandlerClassesAlive() throws Exception {
        byte[] bytes;
        try (var in = CollectableHandler.class.getResourceAsStream("CollectableHandler.class")) {
            bytes = in.readAllBytes();
        }
        var lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
        var type = new WeakReference<Class<?>>(lookup.lookupClass());
        var handler = (PacketReceiveHandler<?>)
                lookup.lookupClass().getDeclaredConstructor().newInstance();
        var route = PacketReceiveRoute.of(new PacketReceiveHandler[] {handler});
        for (int i = 0; i < 3; i++) route.dispatch(null, null, null);
        lookup = null;
        handler = null;
        route = null;
        for (int i = 0; i < 50 && type.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(type.get());
    }

    private static void spun(List<String> calls, int id, PacketReceiveEvent<?> event, CultPlayer player, Object p) {
        calls.add(id + ":" + (player == PLAYER) + ":" + p);
    }

    @SuppressWarnings("unchecked")
    private static PacketReceiveHandler<Object> spin(MethodHandle target, List<String> calls, int id) throws Throwable {
        var erased = MethodType.methodType(void.class, PacketReceiveEvent.class, CultPlayer.class, Object.class);
        var site = LambdaMetafactory.metafactory(
                MethodHandles.lookup(),
                "handle",
                MethodType.methodType(PacketReceiveHandler.class, List.class, int.class),
                erased,
                target,
                erased);
        return (PacketReceiveHandler<Object>) site.getTarget().invoke(calls, id);
    }

    private static int size(Random random) {
        return random.nextInt(3) == 0 ? random.nextInt(70) : SIZES[random.nextInt(SIZES.length)];
    }

    private static void receive(PacketRouteBuilder builder, PacketReceiveHandler<? super ServerboundPong> handler) {
        builder.receive(ServerboundPackets.PONG, PacketListenerPriority.NORMAL, handler);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void loop(
            List<? extends PacketReceiveHandler> handlers, PacketReceiveEvent event, CultPlayer p, Object packet) {
        for (PacketReceiveHandler handler : handlers) handler.handle(event, p, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void loopSend(
            List<? extends PacketSendHandler> handlers, PacketSendEvent event, CultPlayer p, Object packet) {
        for (PacketSendHandler handler : handlers) handler.handle(event, p, packet);
    }

    private static List<PacketReceiveHandler<Object>> receiveHandlers(
            Random random, Trace trace, int count, boolean throwing) {
        List<PacketReceiveHandler<Object>> handlers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = trace.next++;
            int kind = random.nextInt(throwing ? 12 : 9);
            handlers.add(
                    switch (kind) {
                        case 0 -> (event, player, packet) -> trace.call(id, event, player, packet);
                        case 1 ->
                            (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                event.setCancelled(true);
                            };
                        case 2 ->
                            (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                if (event.isCancelled()) event.setCancelled(false);
                            };
                        case 3 ->
                            (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                if (trace.publish != null) trace.publish.run();
                            };
                        case 4 -> scanned(new Recorder(trace, id, null));
                        case 5 -> (event, player, packet) -> trace.calls.add("five " + id);
                        case 6 -> (event, player, packet) -> trace.calls.add("six " + id + event.isCancelled());
                        case 7 -> (event, player, packet) -> trace.call(-id, event, player, packet);
                        case 8 ->
                            (event, player, packet) -> {
                                if (!event.isCancelled()) trace.call(id, event, player, packet);
                            };
                        case 9 -> {
                            var failure = new IllegalArgumentException("runtime " + id);
                            yield (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                throw failure;
                            };
                        }
                        case 10 -> {
                            var failure = new AssertionError("error " + id);
                            yield (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                throw failure;
                            };
                        }
                        default -> scanned(new Recorder(trace, id, new Exception("checked " + id)));
                    });
        }
        return handlers;
    }

    private static List<PacketSendHandler<Object>> sendHandlers(
            Random random, Trace trace, int count, boolean throwing) {
        List<PacketSendHandler<Object>> handlers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = trace.next++;
            int kind = random.nextInt(throwing ? 8 : 6);
            handlers.add(
                    switch (kind) {
                        case 0 -> (event, player, packet) -> trace.call(id, event, player, packet);
                        case 1 ->
                            (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                event.setCancelled(true);
                            };
                        case 2 ->
                            (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                if (event.isCancelled()) event.setCancelled(false);
                            };
                        case 3 -> scannedSend(new Recorder(trace, id, null));
                        case 4 -> (event, player, packet) -> trace.calls.add("four " + id + event.isCancelled());
                        case 5 -> (event, player, packet) -> trace.call(-id, event, player, packet);
                        case 6 -> {
                            var failure = new IllegalStateException("runtime " + id);
                            yield (event, player, packet) -> {
                                trace.call(id, event, player, packet);
                                throw failure;
                            };
                        }
                        default -> scannedSend(new Recorder(trace, id, new Exception("checked " + id)));
                    });
        }
        return handlers;
    }

    private static PacketReceiveHandler<Object> scanned(Recorder recorder) {
        return SCANNER.receiveHandlers(recorder).getFirst().handler();
    }

    private static PacketSendHandler<Object> scannedSend(Recorder recorder) {
        return SCANNER.sendHandlers(recorder).getFirst().handler();
    }

    record Outcome(List<String> calls, boolean cancelled, String failure) {}

    static final class Trace {
        final List<String> calls = new ArrayList<>();
        int next;
        Object expectedPacket;
        Runnable publish;

        void call(int id, Object event, CultPlayer player, Object packet) {
            boolean cancelled = event instanceof PacketReceiveEvent<?> receive
                    ? receive.isCancelled()
                    : ((PacketSendEvent<?>) event).isCancelled();
            calls.add(id
                    + (cancelled ? " c" : " -")
                    + (player == PLAYER ? " p" : player == null ? " n" : " ?")
                    + (packet == expectedPacket ? "" : " wrong packet"));
        }

        Outcome run(boolean cancelled, Consumer<PacketReceiveEvent<ServerboundPong>> path, ServerboundPong packet) {
            var event = new PacketReceiveEvent<>(null, ConnectionPhase.PLAY, ServerboundPackets.PONG, packet);
            event.setCancelled(cancelled);
            return outcome(() -> path.accept(event), event::isCancelled, packet);
        }

        Outcome runSend(boolean cancelled, Consumer<PacketSendEvent<ClientboundPing>> path, ClientboundPing packet) {
            var event = new PacketSendEvent<>(null, ConnectionPhase.PLAY, ClientboundPackets.PING, packet, false);
            event.setCancelled(cancelled);
            return outcome(() -> path.accept(event), event::isCancelled, packet);
        }

        private Outcome outcome(Runnable path, BooleanSupplier cancelled, Object packet) {
            calls.clear();
            expectedPacket = packet;
            String failure = null;
            try {
                path.run();
            } catch (Throwable thrown) {
                failure = thrown.getClass().getName() + ": " + thrown.getMessage() + " @"
                        + System.identityHashCode(thrown.getCause() == null ? thrown : thrown.getCause());
            }
            return new Outcome(List.copyOf(calls), cancelled.getAsBoolean(), failure);
        }
    }

    static final class Recorder {
        private final Trace trace;
        private final int id;
        private final Exception failure;

        Recorder(Trace trace, int id, Exception failure) {
            this.trace = trace;
            this.id = id;
            this.failure = failure;
        }

        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet)
                throws Exception {
            trace.call(id, event, player, packet);
            if (failure != null) throw failure;
        }

        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) throws Exception {
            trace.call(id, event, player, packet);
            if (failure != null) throw failure;
        }
    }
}

/** Defined as a hidden class by its test, so nothing but its instances keeps it loaded. */
final class CollectableHandler implements PacketReceiveHandler<Object> {
    @Override
    public void handle(PacketReceiveEvent event, CultPlayer player, Object packet) {}
}
