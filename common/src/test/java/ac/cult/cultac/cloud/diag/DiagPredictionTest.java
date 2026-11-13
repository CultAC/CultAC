package ac.cult.cultac.cloud.diag;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.data.BlockRegistry;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.impl.prediction.BlockGrid;
import ac.cult.cultac.checks.impl.prediction.PredictionObserver;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SuperDebug;
import ac.cult.cultac.checks.impl.verbose.VerboseCodecs;
import ac.cult.cultac.cloud.intake.v3.DiagCoverage;
import ac.cult.cultac.cloud.intake.v3.DiagPart;
import ac.cult.cultac.cloud.intake.v3.DiagTick;
import ac.cult.cultac.cloud.intake.v3.Intake;
import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.events.packets.listeners.PacketEntityAction;
import ac.cult.cultac.events.packets.listeners.PacketPlayerSteer;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.CultLogViewers;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerCommand;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.cult.cultac.utils.anticheat.NettyScheduler;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.debug.DebugText;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.Collisions;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.api.config.ConfigManager;
import com.github.luben.zstd.ZstdInputStream;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Diagnostics over real predictions: offline Java players on a stone floor beside a wall of stairs, fences, slabs and
 * panes, fed vanilla walking with a speed burst every {@link #CHEAT_EVERY} ticks so the engine flags. A watched
 * player's predictions, flags, violations, {@code /cult log} entries and the bytes written to them match an unwatched
 * player's fed the same ticks; a capture that throws trips diagnostics without touching prediction.
 */
class DiagPredictionTest {
    private static final int FLOOR_Y = 64;
    private static final int FLOOR_RADIUS = 48;
    private static final int CHEAT_EVERY = 23;
    private static final Limits ON = Limits.of(
            true,
            400,
            1 << 20,
            Limits.MAX_SERVER_RING_BYTES,
            Limits.MAX_PLAYERS,
            0,
            Limits.MAX_UPLOAD_BYTES_PER_SECOND,
            0,
            0);

    private static Field dataStore;
    private static Object previousDataStore;

    private final List<Diagnostics> opened = new ArrayList<>();

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        OfflineCultTestBootstrap.installConfig();
        Class.forName(VerboseCodecs.class.getName());
        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        Mockito.when(config.getLongElse(Mockito.anyString(), Mockito.anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // A flag records itself through the data store, which a plugin-less test has to provide.
        dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        dataStore.setAccessible(true);
        previousDataStore = dataStore.get(CultAPI.INSTANCE);
        dataStore.set(
                CultAPI.INSTANCE,
                new DataStoreLifecycle(CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
    }

    @AfterAll
    static void restore() throws IllegalAccessException {
        dataStore.set(CultAPI.INSTANCE, previousDataStore);
    }

    @BeforeEach
    void shippedSetbacks() {
        // Other tests in this JVM leave prediction overrides set; the script's writes are the shipped setbacks.
        clearDouble("Simulation.threshold");
        clearDouble("Simulation.immediate-setback-threshold");
    }

    @AfterEach
    void close() {
        for (Diagnostics diagnostics : opened) diagnostics.close();
        Diagnostics.resetTrip();
    }

    @Test
    void watchingLeavesPredictionUnchanged() throws Exception {
        Diagnostics diagnostics = diagnostics();
        Bot plain = new Bot(0);
        Bot watched = new Bot(0);
        AtomicLong tick = new AtomicLong();
        DiagWatch watch = watched.watch(diagnostics, tick::get);
        assertNotNull(watch);
        // Staff see each Simulation flag's Cult Log identifier, so each such flag gets its log.
        try (AutoCloseable plainStaff = CultLogViewers.watch(plain.player);
                AutoCloseable watchedStaff = CultLogViewers.watch(watched.player)) {
            run(200, tick, plain, watched);
            assertTrue(plain.flagged > 0, "the script makes the engine flag");
            assertFalse(plain.logs.isEmpty(), "the flags staff saw have logs");
            assertEquals(plain.signatures, watched.signatures);
            assertEquals(plain.violations(), watched.violations());
            assertEquals(plain.logs, watched.logs);
            assertTrue(plain.writes > 0, "the script makes cultac write to the player");
            assertFalse(Diagnostics.tripped());
            assertNotNull(watched.debug.watch());

            await(() -> watch.ring != null && watch.ring.newest() >= watched.lastPredicted);
            assertFalse(watch.ended(), String.valueOf(watch.endReason));
            UUID player = watched.player.getUniqueId();
            DiagUpload upload = diagnostics.upload();
            long to = watched.lastPredicted;
            diagnostics.cut(player, new Cut(new byte[16], to - 19, to, to - 9, to), "header");
            List<DiagPart> parts = parts(upload);
            DiagPart last = parts.get(parts.size() - 1);
            assertEquals(DiagCoverage.DIAG_COVERAGE_FULL, last.getCoverage());
            int fulls = 0;
            for (DiagPart part : parts) {
                for (DiagTick diagTick : part.getTicksList()) {
                    if (diagTick.getFull().isEmpty()) continue;
                    fulls++;
                    String text = unzstd(diagTick.getFull().toByteArray());
                    assertTrue(text.contains("Bounding box: minX="), text);
                    assertTrue(text.contains("Starting vel: "), text);
                    assertTrue(text.contains("oak_stairs"), "the block grid is printed");
                }
            }
            assertTrue(fulls >= 10, "full texts " + fulls);
        } finally {
            plain.close();
            watched.close();
        }
        diagnostics.close();
        await(() -> !diagnostics.threadRunning());
        assertNull(watch.ring);
        assertNull(watched.debug.watch());
    }

    @Test
    void aCaptureThatThrowsTripsDiagnosticsAndPredictionGoesOn() {
        Diagnostics diagnostics = diagnostics();
        Bot plain = new Bot(1);
        AtomicLong tick = new AtomicLong();
        Bot failing = new Bot(1).counting(tick::get);
        DiagWatch watch = failing.watch(diagnostics, () -> {
            if (tick.get() >= 20) throw new IllegalStateException("injected capture failure");
            return tick.get();
        });
        try {
            run(120, tick, plain, failing);
            assertTrue(Diagnostics.tripped());
            assertTrue(watch.ended());
            assertEquals("failed", watch.endReason);
            assertNull(failing.debug.watch());
            assertEquals(plain.signatures, failing.signatures);
            assertEquals(plain.violations(), failing.violations());
            assertTrue(diagnostics.status().contains("tripped"));
        } finally {
            plain.close();
            failing.close();
        }
    }

    /**
     * Every bundle a watched player's recorded states upload is byte for byte the bundle of the same states printed and
     * compressed as each prediction completed: cuts at the start, middle and end of a ring that has evicted ticks, a cut
     * still waiting when its watch stops, and cuts across a watch that starts again. The recorded states reach their
     * diagnostics a few ticks late and are printed only when a flag or a cut asks for them, up to a hundred ticks
     * after the player's state has moved on.
     */
    @Test
    void recordedStatesUploadAsTheTextPrintedAtCapture() throws Exception {
        Limits limits = Limits.of(
                true,
                120,
                Limits.MAX_PLAYER_RING_BYTES,
                Limits.MAX_SERVER_RING_BYTES,
                Limits.MAX_PLAYERS,
                0,
                Limits.MAX_UPLOAD_BYTES_PER_SECOND,
                0,
                Limits.MAX_BUNDLES_PER_HOUR);
        Diagnostics printed = diagnostics(limits);
        Diagnostics recorded = diagnostics(limits);
        AtomicLong tick = new AtomicLong();
        Bot bot = new Bot(2).counting(tick::get);
        Tee tee = new Tee(tick::get);
        List<List<byte[]>> bundles = new ArrayList<>();
        try {
            tee.start(printed, recorded, bot);
            for (int i = 0; i < 200; i++) tee.tick(bot, tick);
            long newest = tee.settle(bot);
            assertEquals(120, tee.recorded.ring.size(), "the ring evicted its oldest ticks");
            int held = 0;
            for (DiagRing.Tick kept : tee.recorded.ring.range(0, newest)) if (kept.state != null) held++;
            assertTrue(held >= 100, "states held unprinted " + held);
            assertEquals(
                    0,
                    tee.printed.ring.range(0, newest).stream()
                            .filter(kept -> kept.state != null)
                            .count());
            tee.cut(bundles, 1, newest - 119, newest - 100, newest - 119, newest - 110);
            tee.cut(bundles, 2, newest - 70, newest - 40, newest - 60, newest - 50);
            tee.cut(bundles, 3, newest - 19, newest, newest - 9, newest);
            tee.cut(bundles, 4, newest - 160, newest - 110, newest - 130, newest - 115);
            tee.cut(bundles, 5, 0, newest, newest - 119, newest);

            tee.cutLater(6, newest - 5, newest + 20, newest - 5, newest + 5);
            for (int i = 0; i < 8; i++) tee.tick(bot, tick);
            tee.settle(bot);
            tee.stop(printed, recorded, bot);
            bundles.add(tee.collect(printed, recorded));

            for (int i = 0; i < 30; i++) tee.tick(bot, tick);
            tee.start(printed, recorded, bot);
            for (int i = 0; i < 60; i++) tee.tick(bot, tick);
            newest = tee.settle(bot);
            tee.cut(bundles, 7, newest - 80, newest - 40, newest - 70, newest - 50);
            tee.cut(bundles, 8, newest - 30, newest, newest - 15, newest - 5);
            tee.cut(bundles, 9, newest - 59, newest, newest - 59, newest);
        } finally {
            bot.close();
        }
        assertEquals(9, bundles.size());
        int fulls = 0;
        for (List<byte[]> parts : bundles) {
            for (byte[] part : parts) {
                for (DiagTick diagTick :
                        Intake.parseFrom(part).getMessages(0).getDiag().getTicksList()) {
                    if (!diagTick.getFull().isEmpty()) fulls++;
                }
            }
        }
        assertTrue(fulls >= 40, "full texts " + fulls);
    }

    /**
     * Hands each prediction to a watch of {@code printed} as the string it prints at once, and to a watch of
     * {@code recorded} as its recorded state, held for a few ticks; tick ends follow each the same way.
     */
    private static final class Tee implements PredictionObserver {
        private static final int HOLD_TICKS = 7;
        private static final byte[] HEADER_BUNDLE = new byte[16];

        final LongSupplier seq;
        final List<Runnable> held = new ArrayList<>();
        DiagWatch printed;
        DiagWatch recorded;
        private Diagnostics printedOwner;
        private Diagnostics recordedOwner;
        private UUID player;

        Tee(LongSupplier seq) {
            this.seq = seq;
        }

        void start(Diagnostics printed, Diagnostics recorded, Bot bot) {
            printedOwner = printed;
            recordedOwner = recorded;
            player = bot.player.getUniqueId();
            this.printed = printed.watch(player, 1, 3600, seq, bot.debug::history, watch -> {});
            this.recorded = recorded.watch(player, 1, 3600, seq, bot.debug::history, watch -> {});
            assertNotNull(this.printed);
            assertNotNull(this.recorded);
            bot.debug.watch(this);
        }

        void stop(Diagnostics printed, Diagnostics recorded, Bot bot) {
            release();
            printed.watch(player, 1, 0, seq, bot.debug::history, watch -> {});
            recorded.watch(player, 1, 0, seq, bot.debug::history, watch -> {});
            assertTrue(this.printed.ended() && this.recorded.ended());
            bot.debug.unwatch(this);
        }

        void tick(Bot bot, AtomicLong tick) {
            PredictionResult before =
                    bot.player.checkManager.getSimulationProcessor().getLastPrediction();
            bot.tick();
            PredictionResult result =
                    bot.player.checkManager.getSimulationProcessor().getLastPrediction();
            if (result != null && result != before && result.hasEffectiveFlags()) {
                List<String> flags = new ArrayList<>();
                for (PredictionResult.Flag flag : result.getFlags())
                    flags.add(flag.getCheck().getCheckName());
                long at = tick.get();
                printed.tickEnd(at, flags);
                DiagWatch watch = recorded;
                held.add(() -> watch.tickEnd(at, flags));
            }
            tick.incrementAndGet();
            if (tick.get() % HOLD_TICKS == 0) release();
        }

        /** Releases what is held and waits for both rings to take it; the newest seq predicted. */
        long settle(Bot bot) {
            release();
            long newest = bot.lastPredicted;
            await(() -> printed.ring != null && printed.ring.newest() >= newest);
            await(() -> recorded.ring != null && recorded.ring.newest() >= newest);
            return newest;
        }

        void cut(List<List<byte[]>> bundles, int id, long from, long to, long fullFrom, long fullTo) {
            cutLater(id, from, to, fullFrom, fullTo);
            bundles.add(collect(printedOwner, recordedOwner));
        }

        void cutLater(int id, long from, long to, long fullFrom, long fullTo) {
            byte[] bundle = HEADER_BUNDLE.clone();
            bundle[0] = (byte) id;
            printedOwner.cut(player, new Cut(bundle, from, to, fullFrom, fullTo), "header");
            recordedOwner.cut(player, new Cut(bundle.clone(), from, to, fullFrom, fullTo), "header");
        }

        /** The next bundle each uploads, which must be the same bytes. */
        List<byte[]> collect(Diagnostics printed, Diagnostics recorded) {
            List<byte[]> expected = records(printed.upload());
            List<byte[]> actual = records(recorded.upload());
            assertEquals(expected.size(), actual.size(), "parts");
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), "part " + i);
            }
            return actual;
        }

        private void release() {
            for (Runnable op : held) op.run();
            held.clear();
        }

        @Override
        public long pendingSeq() {
            return seq.getAsLong();
        }

        @Override
        public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long startNanos) {
            printed.capture(seq, offset, trace, state, grid, startNanos);
            DiagWatch watch = recorded;
            held.add(() -> watch.capture(seq, offset, trace, state, grid, startNanos));
        }

        @Override
        public void captureText(
                long seq, double offset, String trace, DebugText state, BlockGrid grid, long startNanos) {
            printed.capture(seq, offset, trace, state == null ? null : state.toString(), grid, startNanos);
            DiagWatch watch = recorded;
            held.add(() -> watch.captureText(seq, offset, trace, state, grid, startNanos));
        }

        @Override
        public void failed(RuntimeException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * With {@code CULTAC_DIAG_CAPTURE_BENCH} naming a file, appends the bytes and cpu a watched player's tick costs:
     * its state printed to a string on the packet thread and then framed, against recorded and printed straight into
     * the frame's text, over a real walk's states; and the whole watch, both threads, with and without cuts.
     */
    @Test
    void captureCost() throws Exception {
        String out = System.getenv("CULTAC_DIAG_CAPTURE_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        StringBuilder report = new StringBuilder();
        textCost(report);
        for (boolean cuts : new boolean[] {false, true}) watchCost(report, cuts);
        Files.writeString(Path.of(out), report.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * The held states of a real walk, measured in turn as the ring measures them, together hold no more of the heap
     * than their estimates add up to: counted with 16-byte headers, two bytes a char and the heap's reference width,
     * less enums and the strings the text keeps shared.
     */
    @Test
    void heldStatesHoldNoMoreOfTheHeapThanTheirEstimates() throws Exception {
        AtomicLong tick = new AtomicLong();
        Bot bot = new Bot(3).counting(tick::get);
        List<DiagWatch.Raw> raws = new ArrayList<>();
        bot.debug.watch(new PredictionObserver() {
            @Override
            public long pendingSeq() {
                return tick.get();
            }

            @Override
            public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long start) {}

            @Override
            public void captureText(
                    long seq, double offset, String trace, DebugText state, BlockGrid grid, long start) {
                if (state != null) raws.add(new DiagWatch.Raw(null, seq, 0, "", state, grid, 0, true));
            }

            @Override
            public void failed(RuntimeException failure) {
                throw new AssertionError(failure);
            }
        });
        try {
            for (int i = 0; i < 400; i++) {
                bot.tick();
                tick.incrementAndGet();
            }
        } finally {
            bot.close();
        }
        assertTrue(raws.size() >= 300, "states " + raws.size());
        long estimated = 0;
        for (DiagWatch.Raw raw : raws) estimated += Diagnostics.retained(raw);

        Map<Object, Boolean> seen = new IdentityHashMap<>();
        seen.put("", Boolean.TRUE);
        for (Object shared : (Object[]) staticField("ASCII")) seen.put(shared, Boolean.TRUE);
        AtomicReferenceArray<?> shared = (AtomicReferenceArray<?>) staticField("SHARED");
        for (int i = 0; i < shared.length(); i++) if (shared.get(i) != null) seen.put(shared.get(i), Boolean.TRUE);
        int reference = referenceBytes();
        long heap = 0;
        for (DiagWatch.Raw raw : raws) heap += heapBytes(raw, seen, reference);
        assertTrue(estimated >= heap, "estimates " + estimated + " < heap " + heap);
    }

    private static Object staticField(String name) throws ReflectiveOperationException {
        Field field = DebugText.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static int referenceBytes() {
        HotSpotDiagnosticMXBean vm = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        return Boolean.parseBoolean(vm.getVMOption("UseCompressedOops").getValue()) ? 4 : 8;
    }

    /** The heap {@code value} reaches that {@code seen} does not hold yet, in the layout the test above names. */
    private static long heapBytes(Object value, Map<Object, Boolean> seen, int reference)
            throws IllegalAccessException {
        if (value == null || value instanceof Enum<?> || value instanceof Class<?>) return 0;
        if (seen.put(value, Boolean.TRUE) != null) return 0;
        if (value instanceof String text) return aligned(16 + reference + 6) + aligned(20 + 2L * text.length());
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) return 24;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            if (!(value instanceof Object[] values)) {
                return aligned(20 + (long) width(type.getComponentType(), reference) * length);
            }
            long bytes = aligned(20 + (long) reference * length);
            for (Object element : values) bytes += heapBytes(element, seen, reference);
            return bytes;
        }
        long own = 16;
        List<Object> references = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                own += width(field.getType(), reference);
                if (field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                references.add(field.get(value));
            }
        }
        long bytes = aligned(own);
        for (Object field : references) bytes += heapBytes(field, seen, reference);
        return bytes;
    }

    private static int width(Class<?> type, int reference) {
        if (type == byte.class || type == boolean.class) return 1;
        if (type == short.class || type == char.class) return 2;
        if (type == int.class || type == float.class) return 4;
        return type.isPrimitive() ? 8 : reference;
    }

    private static long aligned(long bytes) {
        return (bytes + 7) & ~7L;
    }

    private static void textCost(StringBuilder report) throws ReflectiveOperationException {
        AtomicLong tick = new AtomicLong();
        Bot bot = new Bot(3).counting(tick::get);
        List<DebugText> states = new ArrayList<>();
        List<BlockGrid> grids = new ArrayList<>();
        bot.debug.watch(new PredictionObserver() {
            @Override
            public long pendingSeq() {
                return tick.get();
            }

            @Override
            public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long start) {}

            @Override
            public void captureText(
                    long seq, double offset, String trace, DebugText state, BlockGrid grid, long start) {
                if (state == null) return;
                states.add(state);
                grids.add(grid);
            }

            @Override
            public void failed(RuntimeException failure) {
                throw new AssertionError(failure);
            }
        });
        try {
            for (int i = 0; i < 400; i++) {
                bot.tick();
                tick.incrementAndGet();
            }
        } finally {
            bot.close();
        }
        Field textParts = DebugText.class.getDeclaredField("parts");
        Field textSize = DebugText.class.getDeclaredField("size");
        Field textNumbers = DebugText.class.getDeclaredField("numbers");
        Field builderParts = DebugText.Builder.class.getDeclaredField("parts");
        Field builderNumbers = DebugText.Builder.class.getDeclaredField("numbers");
        Field builderSize = DebugText.Builder.class.getDeclaredField("size");
        Field builderCount = DebugText.Builder.class.getDeclaredField("count");
        for (Field field :
                List.of(textParts, textSize, textNumbers, builderParts, builderNumbers, builderSize, builderCount)) {
            field.setAccessible(true);
        }
        int n = states.size();
        Object[][] parts = new Object[n][];
        long[][] numbers = new long[n][];
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < n; i++) {
            parts[i] = Arrays.copyOf((Object[]) textParts.get(states.get(i)), textSize.getInt(states.get(i)));
            int count = 0;
            for (Object part : parts[i]) {
                if (part instanceof Enum<?> && part.getClass().getEnclosingClass() == DebugText.class) count++;
            }
            numbers[i] = Arrays.copyOf((long[]) textNumbers.get(states.get(i)), count);
        }
        // What printing each state's vectors through their toString allocates.
        long vectorBytes = 0;
        long vectorChars = 0;
        for (int warm = 0; warm < 3; warm++) {
            long before = threads.getThreadAllocatedBytes(thread);
            for (Object[] state : parts) {
                for (Object part : state) {
                    if (part.getClass() == Vec3.class)
                        vectorChars += part.toString().length();
                }
            }
            vectorBytes = (threads.getThreadAllocatedBytes(thread) - before) / n;
        }
        int reps = 11;
        int passes = 16;
        String[] names = {"string+frame", "record+frame", "string", "record"};
        double[][] alloc = new double[4][reps];
        double[][] cpu = new double[4][reps];
        long sink = 0;
        try (DiagText text = new DiagText(1)) {
            for (int rep = -4; rep < reps; rep++) {
                for (int pair = 0; pair < 2; pair++) {
                    for (int order = 0; order < 2; order++) {
                        int way = pair * 2 + (((rep & 1) == 0) == (order == 0) ? 0 : 1);
                        long bytes = threads.getThreadAllocatedBytes(thread);
                        long time = threads.getCurrentThreadCpuTime();
                        for (int k = 0; k < n * passes; k++) {
                            int i = k % n;
                            DebugText.Builder sb = new DebugText.Builder(parts[i].length + 64, numbers[i].length + 32);
                            System.arraycopy(parts[i], 0, builderParts.get(sb), 0, parts[i].length);
                            System.arraycopy(numbers[i], 0, builderNumbers.get(sb), 0, numbers[i].length);
                            builderSize.setInt(sb, parts[i].length);
                            builderCount.setInt(sb, numbers[i].length);
                            switch (way) {
                                case 0 -> sink += text.compress(DiagTextTest.kept(sb.toString()), grids.get(i)).length;
                                case 1 -> sink += text.compress(sb.toText(), grids.get(i)).length;
                                case 2 -> sink += sb.toString().length();
                                default -> sink += sb.toText().hashCode() & 1;
                            }
                        }
                        if (rep < 0) continue;
                        alloc[way][rep] = (threads.getThreadAllocatedBytes(thread) - bytes) / (double) (n * passes);
                        cpu[way][rep] = (threads.getCurrentThreadCpuTime() - time) / (double) (n * passes);
                    }
                }
            }
        }
        report.append(String.format(
                Locale.ROOT,
                "diag capture text, %d states (sink %d), Vec3 strings %d B/state (%d chars):",
                n,
                sink,
                vectorBytes,
                vectorChars / 3 / n));
        for (int way = 0; way < 4; way++) median(report, names[way], alloc[way], cpu[way]);
        report.append('\n');
    }

    private void watchCost(StringBuilder report, boolean cuts) throws Exception {
        Diagnostics diagnostics = diagnostics();
        AtomicLong tick = new AtomicLong();
        Bot bot = new Bot(4).counting(tick::get);
        DiagWatch watch = diagnostics.watch(bot.player.getUniqueId(), 1, 3600, tick::get, bot.debug::history, w -> {});
        boolean[] printed = new boolean[1];
        bot.debug.watch(new PredictionObserver() {
            @Override
            public long pendingSeq() {
                return tick.get();
            }

            @Override
            public void capture(long seq, double offset, String trace, String state, BlockGrid grid, long start) {
                watch.capture(seq, offset, trace, state, grid, start);
            }

            @Override
            public void captureText(
                    long seq, double offset, String trace, DebugText state, BlockGrid grid, long start) {
                if (printed[0]) watch.capture(seq, offset, trace, state == null ? null : state.toString(), grid, start);
                else watch.captureText(seq, offset, trace, state, grid, start);
            }

            @Override
            public void failed(RuntimeException failure) {
                throw new AssertionError(failure);
            }
        });
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().threadId();
        int block = 1200;
        int reps = 9;
        double[][] alloc = new double[4][reps];
        double[][] cpu = new double[4][reps];
        try {
            for (int i = 0; i < 20; i++) {
                bot.tick();
                tick.incrementAndGet();
            }
            long newestBefore = bot.lastPredicted;
            await(() -> watch.ring != null && watch.ring.newest() >= newestBefore);
            long diag = diagThread();
            int bundle = 0;
            for (int rep = -3; rep < reps; rep++) {
                for (int order = 0; order < 2; order++) {
                    boolean usePrinted = ((rep & 1) == 0) == (order == 0);
                    printed[0] = usePrinted;
                    long bytes = threads.getThreadAllocatedBytes(self);
                    long time = threads.getCurrentThreadCpuTime();
                    long diagBytes = threads.getThreadAllocatedBytes(diag);
                    long diagTime = threads.getThreadCpuTime(diag);
                    int predicted = 0;
                    for (int i = 0; i < block; i++) {
                        long last = bot.lastPredicted;
                        bot.tick();
                        tick.incrementAndGet();
                        if (bot.lastPredicted != last) predicted++;
                        if (i % 128 == 127) {
                            long caught = bot.lastPredicted;
                            await(() -> watch.ring.newest() >= caught);
                        }
                    }
                    long packetBytes = threads.getThreadAllocatedBytes(self) - bytes;
                    long packetTime = threads.getCurrentThreadCpuTime() - time;
                    long newest = bot.lastPredicted;
                    await(() -> watch.ring.newest() >= newest);
                    if (cuts) {
                        byte[] id = new byte[16];
                        id[0] = (byte) ++bundle;
                        diagnostics.cut(
                                bot.player.getUniqueId(),
                                new Cut(id, newest - 40, newest, newest - 20, newest),
                                "header");
                        records(diagnostics.upload());
                    }
                    long diagBytesUsed = threads.getThreadAllocatedBytes(diag) - diagBytes;
                    long diagTimeUsed = threads.getThreadCpuTime(diag) - diagTime;
                    if (rep < 0) continue;
                    int way = usePrinted ? 0 : 1;
                    alloc[way][rep] = packetBytes / (double) predicted;
                    cpu[way][rep] = packetTime / (double) predicted;
                    alloc[way + 2][rep] = diagBytesUsed / (double) predicted;
                    cpu[way + 2][rep] = diagTimeUsed / (double) predicted;
                }
            }
        } finally {
            bot.close();
            diagnostics.close();
        }
        await(() -> !diagnostics.threadRunning());
        report.append(cuts ? "diag watch, cut each block:" : "diag watch, no cuts:");
        String[] names = {"packet string", "packet record", "diag string", "diag record"};
        for (int way = 0; way < 4; way++) median(report, names[way], alloc[way], cpu[way]);
        report.append('\n');
    }

    private static long diagThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("cult-cloud-diag-") && thread.isAlive()) return thread.threadId();
        }
        throw new AssertionError("no diag thread");
    }

    private static void median(StringBuilder report, String name, double[] alloc, double[] cpu) {
        Arrays.sort(alloc);
        Arrays.sort(cpu);
        int n = alloc.length;
        report.append(String.format(
                Locale.ROOT,
                " %s alloc median %.0f B (min %.0f, max %.0f), cpu median %.0f ns (min %.0f, max %.0f);",
                name,
                alloc[n / 2],
                alloc[0],
                alloc[n - 1],
                cpu[n / 2],
                cpu[0],
                cpu[n - 1]));
    }

    /** One bundle's records, through its last part. */
    private static List<byte[]> records(DiagUpload upload) {
        List<byte[]> records = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long nanos = 0;
        while (System.nanoTime() < deadline) {
            byte[] record = upload.poll(1, nanos += TimeUnit.SECONDS.toNanos(10));
            if (record == null) {
                sleep(5);
                continue;
            }
            records.add(record);
            try {
                if (Intake.parseFrom(record).getMessages(0).getDiag().getLast()) return records;
            } catch (IOException malformed) {
                throw new AssertionError(malformed);
            }
        }
        throw new AssertionError("no last part");
    }

    private static void clearDouble(String key) {
        try {
            Method clear = OfflineCultTestBootstrap.class.getDeclaredMethod("clearDoubleConfigOverride", String.class);
            clear.setAccessible(true);
            clear.invoke(null, key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Diagnostics diagnostics() {
        return diagnostics(ON);
    }

    private Diagnostics diagnostics(Limits limits) {
        Diagnostics diagnostics = new Diagnostics(new DiagUpload(), "prediction-test");
        diagnostics.captureLimitNanos = Long.MAX_VALUE;
        diagnostics.connected(1, limits);
        opened.add(diagnostics);
        return diagnostics;
    }

    /** {@code ticks} client ticks for every bot, round-robin at 20 a second so the timer checks see vanilla pacing. */
    private static void run(int ticks, AtomicLong tick, Bot... bots) {
        long origin = System.nanoTime();
        for (int slot = 0; slot < ticks; slot++) {
            for (Bot bot : bots) bot.tick();
            tick.incrementAndGet();
            long next = origin + (slot + 1) * 50_000_000L;
            while (System.nanoTime() < next) Thread.onSpinWait();
        }
    }

    private static List<DiagPart> parts(DiagUpload upload) {
        List<DiagPart> parts = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long nanos = 0;
        while (System.nanoTime() < deadline) {
            byte[] record = upload.poll(1, nanos += TimeUnit.SECONDS.toNanos(10));
            if (record == null) {
                sleep(5);
                continue;
            }
            try {
                DiagPart part = Intake.parseFrom(record).getMessages(0).getDiag();
                parts.add(part);
                if (part.getLast()) return parts;
            } catch (IOException malformed) {
                throw new AssertionError(malformed);
            }
        }
        throw new AssertionError("no last part");
    }

    private static String unzstd(byte[] frame) throws IOException {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One offline player and the walk it is fed; what each tick's prediction concluded is kept. */
    private static final class Bot {
        private static final CheckManagerListener LISTENER = new CheckManagerListener();
        private static final PacketEntityAction ACTIONS = new PacketEntityAction();
        private static final PacketPlayerSteer STEER = new PacketPlayerSteer();

        final CultPlayer player = player();
        final EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
        final SuperDebug debug = player.checkManager.getListener(SuperDebug.class);
        final Mover mover;
        final List<String> signatures = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int flagged;
        int writes;
        /** The seq of the last tick whose movement was predicted, on the watch's count. */
        long lastPredicted = -1;

        private static final LongSupplier NO_SEQ = () -> -1;
        private LongSupplier seq = NO_SEQ;

        /** Counts this bot's ticks on {@code ticks}, not on its watch's seq. */
        Bot counting(LongSupplier ticks) {
            seq = ticks;
            return this;
        }

        private PredictionResult last;

        Bot(int seed) {
            mover = new Mover(seed);
            seed(player, mover);
        }

        DiagWatch watch(Diagnostics diagnostics, LongSupplier seq) {
            if (this.seq == NO_SEQ) this.seq = seq;
            DiagWatch watch = diagnostics.watch(player.getUniqueId(), 1, 3600, seq, debug::history, debug::unwatch);
            debug.watch(watch);
            return watch;
        }

        void tick() {
            Step step = mover.tick();
            if (step.input != null) {
                var input = event(ServerboundPackets.PLAYER_INPUT, step.input);
                STEER.onPlayerInput(input, player, step.input);
                CheckManagerListener.receiveGeneric(input, player);
            }
            if (step.sprintChange != null) {
                ServerboundPlayerCommand command = new ServerboundPlayerCommand(0, step.sprintChange, 0);
                var event = event(ServerboundPackets.PLAYER_COMMAND, command);
                ACTIONS.onPlayerCommand(event, player, command);
                LISTENER.onPlayerCommand(event, player, command);
            }
            ServerboundMovePlayer move = new ServerboundMovePlayer(
                    mover.x, mover.y, mover.z, mover.yaw, mover.pitch, mover.onGround, false, true, true);
            LISTENER.onMovePlayer(event(ServerboundPackets.MOVE_PLAYER, move), player, move);
            LISTENER.processClientTickEndReceive(
                    event(ServerboundPackets.CLIENT_TICK_END, ServerboundPackets.CLIENT_TICK_END.opaqueValue()),
                    player);
            String written = written();
            PredictionResult result =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (result == null || result == last) {
                signatures.add("- writes" + written);
                return;
            }
            last = result;
            lastPredicted = seq.getAsLong();
            StringBuilder signature = new StringBuilder(written);
            signature.append(result.getOffset()).append(' ').append(result.isExempt());
            for (PredictionResult.Flag flag : result.getFlags()) {
                signature
                        .append(' ')
                        .append(flag.getCheck().getCheckName())
                        .append('=')
                        .append(flag.getVerbose().getString());
            }
            signature.append(" setback ").append(player.getSetbackTeleportUtil().isPendingSetback());
            signatures.add(signature.toString());
            if (result.hasEffectiveFlags()) flagged++;
            if (result.getIdentifier() != 0) {
                String log = SuperDebug.getFlag(result.getIdentifier());
                logs.add(
                        log == null
                                ? null
                                : log.replaceAll("(?m)^(Time|Player Name): .*$", "")
                                        .replaceAll("@[0-9a-f]+", "@"));
            }
        }

        Map<String, Double> violations() {
            Map<String, Double> violations = new TreeMap<>();
            for (Check check : player.checkManager.getAllChecks()) {
                violations.merge(check.getClass().getName(), check.violations, Double::sum);
            }
            return violations;
        }

        /**
         * What cultac wrote to the player this tick, in order: each write's kind. The transaction pings its scheduler
         * sends on a wall-clock timer are left out; they follow each player's own start, not its predictions.
         */
        String written() {
            StringBuilder written = new StringBuilder();
            channel.runPendingTasks();
            channel.flushOutbound();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                Object kind = message instanceof CultConnection.WriteTask task ? task.action() : message;
                String name = kind.getClass().getName().replaceAll("/0x[0-9a-f]+", "");
                ReferenceCountUtil.release(message);
                if (name.startsWith(NettyScheduler.class.getName())) continue;
                writes++;
                written.append(' ').append(name);
            }
            return written.toString();
        }

        void close() {
            player.onRemove();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }

        private <T extends ServerboundPacket> PacketReceiveEvent<T> event(PacketType<T> type, T packet) {
            return new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, type, packet);
        }
    }

    private static CultPlayer player() {
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Diag_Bench"), new EmbeddedChannel());
        CultPlayer player = new CultPlayer(user);
        player.gamemode = GameMode.SURVIVAL;
        player.canFly = false;
        player.isFlying = false;
        CompensatedWorld world = player.compensatedWorld;
        BlockRegistry registry = DataTables.defaults().registry();
        for (int cx = -FLOOR_RADIUS >> 4; cx <= FLOOR_RADIUS >> 4; cx++) {
            for (int cz = -FLOOR_RADIUS >> 4; cz <= FLOOR_RADIUS >> 4; cz++) {
                world.putChunk(cx, cz, new CompensatedWorld.CachedChunk(new CompensatedWorld.CachedSection[16], 0));
            }
        }
        for (int x = -FLOOR_RADIUS; x < FLOOR_RADIUS; x++) {
            for (int z = -FLOOR_RADIUS; z < FLOOR_RADIUS; z++) {
                world.applyBlockChangeRawDANGER(x, FLOOR_Y - 1, z, state(registry, "stone"));
            }
        }
        int[] wall = {
            state(registry, "oak_stairs"),
            state(registry, "oak_fence"),
            state(registry, "stone_slab"),
            state(registry, "glass_pane"),
            state(registry, "chest"),
            state(registry, "redstone_wire")
        };
        for (int z = -FLOOR_RADIUS; z < FLOOR_RADIUS; z++) {
            for (int y = FLOOR_Y; y <= FLOOR_Y + 1; y++) {
                world.applyBlockChangeRawDANGER(-2, y, z, wall[Math.floorMod(z + y, wall.length)]);
                world.applyBlockChangeRawDANGER(3, y, z, wall[Math.floorMod(z * 7 + y, wall.length)]);
            }
        }
        return player;
    }

    private static int state(BlockRegistry registry, String block) {
        return registry.block("minecraft:" + block).defaultState();
    }

    private static void seed(CultPlayer player, Mover mover) {
        player.x = player.lastX = mover.x;
        player.y = player.lastY = mover.y;
        player.z = player.lastZ = mover.z;
        player.xRot = player.lastTickXRot = mover.yaw;
        player.yRot = player.lastTickYRot = mover.pitch;
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, mover.x, mover.y, mover.z);
        boolean onGround = Collisions.collide(player, 0, -SimpleCollisionBox.COLLISION_EPSILON, 0).y == 0;
        player.onGround = onGround;
        player.lastOnGround = onGround;
        player.checkManager.getSimulationProcessor().seedStartingVelocity(Vec3.ZERO);
    }

    private record Step(ServerboundPlayerInput input, PlayerCommandAction sprintChange) {}

    /**
     * A Java client walking on flat stone along z, turning every 150 ticks, per LivingEntity#aiStep and travel:
     * friction 0.6, gravity 0.08, drag 0.98. Every {@link #CHEAT_EVERY} ticks it moves 0.35 further than it may.
     */
    private static final class Mover {
        double x = 0.5;
        double y = FLOOR_Y;
        double z;
        double vx;
        double vy = (0 - 0.08) * 0.98F;
        double vz;
        float yaw;
        float pitch = 10;
        boolean onGround = true;
        int tick;
        ServerboundPlayerInput lastInput;

        Mover(int seed) {
            z = -20.5 + seed % 5;
            tick = seed * 11;
        }

        Step tick() {
            tick++;
            yaw = tick / 150 % 2 == 0 ? 0 : 180;
            ServerboundPlayerInput input = new ServerboundPlayerInput(true, false, false, false, false, false, false);
            ServerboundPlayerInput changed = input.equals(lastInput) ? null : input;
            lastInput = input;
            PlayerCommandAction sprintChange = null;
            if (vx * vx + vz * vz < 9.0E-6) {
                vx = 0;
                vz = 0;
            }
            if (Math.abs(vy) < 0.003) vy = 0;
            float speed = onGround ? 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F)) : 0.02F;
            float forward = 0.98F * speed;
            float angle = yaw * ((float) Math.PI / 180F);
            vx += -Math.sin(angle) * forward;
            vz += Math.cos(angle) * forward;
            double dy = vy;
            if (y + dy < FLOOR_Y) {
                dy = FLOOR_Y - y;
                onGround = vy < 0;
                vy = 0;
            } else {
                onGround = false;
            }
            x += vx;
            y += dy;
            z += vz;
            if (tick % CHEAT_EVERY == 0) z += yaw == 0 ? 0.35 : -0.35;
            float friction = (onGround ? 0.6F : 1.0F) * 0.91F;
            vx *= friction;
            vy = (vy - 0.08) * 0.98F;
            vz *= friction;
            return new Step(changed, sprintChange);
        }
    }
}
