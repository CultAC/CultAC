package ac.cult.cultac.checks.impl.combat;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.PlatformConnection;
import ac.cult.cultac.network.TestProtocolRuntime;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * Thread CPU, allocation, process CPU and collections of proof-task traffic: relative moves of tracked players and mobs
 * proven by their transaction or by their FairReach deadline, and entity data. Runs only with CULT_PROOF_TASK_BENCH set
 * to an output file. With CULT_PROOF_TASK_BENCH_BASE set to a revision, the main sources that differ from it are
 * compiled at that revision and the base and working-tree code run interleaved in one JVM, each in its own class loader,
 * twice each, so the two base loaders give the noise of JIT and machine; CULT_PROOF_TASK_BENCH_PROCESSORS is the annotation processor path for
 * that compile.
 */
class ProofTaskBenchTest {
    private static final int PLAYERS = 20;
    private static final int MOBS = 40;
    private static final int WARMUP_CYCLES = 20_000;
    private static final int BATCH = 20;
    private static final long REP_CPU_NANOS = 200_000_000L;
    private static final int REPS = 30;
    private static final int BLOCK_REPS = 5;
    private static final int[] MOB_TYPES = {
        EntityTypeIds.ZOMBIE, EntityTypeIds.PIG, EntityTypeIds.ARMOR_STAND, EntityTypeIds.CHICKEN, EntityTypeIds.ITEM
    };
    private static final String[] PROTOCOLS = {"V1_21_11", "V26_3"};

    @Test
    void proofTasks() throws Exception {
        String out = System.getenv("CULT_PROOF_TASK_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        URL[] classpath = classpath();
        String baseRevision = System.getenv("CULT_PROOF_TASK_BENCH_BASE");
        List<String> variants = new ArrayList<>();
        List<ClassLoader> loaders = new ArrayList<>();
        if (baseRevision != null && !baseRevision.isEmpty()) {
            URL base = compileBase(baseRevision, System.getenv("CULT_PROOF_TASK_BENCH_PROCESSORS"))
                    .toUri()
                    .toURL();
            variants.addAll(List.of("base", "new", "base2", "new2"));
            loaders.add(loader(base, classpath));
            loaders.add(loader(null, classpath));
            loaders.add(loader(base, classpath));
            loaders.add(loader(null, classpath));
        } else {
            variants.addAll(List.of("new", "new2"));
            loaders.add(loader(null, classpath));
            loaders.add(loader(null, classpath));
        }
        StringBuilder report = new StringBuilder();
        report.append(String.format(
                "variants %s, base %s, %s, %d reps x %d ms thread CPU in blocks of %d%n",
                variants,
                baseRevision,
                ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .map(GarbageCollectorMXBean::getName)
                        .toList(),
                REPS,
                REP_CPU_NANOS / 1_000_000,
                BLOCK_REPS));
        for (String protocol : PROTOCOLS) report.append(measure(protocol, variants, loaders));
        System.out.print(report);
        Files.writeString(Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String measure(String protocol, List<String> variants, List<ClassLoader> loaders) throws Exception {
        int v = variants.size();
        IntConsumer[][] cycles = new IntConsumer[v][];
        AutoCloseable[] closers = new AutoCloseable[v];
        String[] names = null;
        Thread current = Thread.currentThread();
        ClassLoader context = current.getContextClassLoader();
        try {
            for (int i = 0; i < v; i++) {
                // Service loaders (mock makers among them) resolve through the context class loader.
                current.setContextClassLoader(loaders.get(i));
                Class<?> workload = Class.forName(Workload.class.getName(), true, loaders.get(i));
                var create = workload.getDeclaredMethod("create", String.class);
                create.setAccessible(true);
                Object[] created = (Object[]) create.invoke(null, protocol);
                names = (String[]) created[0];
                cycles[i] = (IntConsumer[]) created[1];
                closers[i] = (AutoCloseable) created[2];
            }
            int c = names.length;
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < v; i++) {
                    current.setContextClassLoader(loaders.get(i));
                    for (int k = 0; k < c; k++) {
                        for (int n = 0; n < WARMUP_CYCLES / BATCH / 2; n++) cycles[i][k].accept(BATCH);
                    }
                }
            }
            // Old-generation proof tasks, as in a session that has run for hours.
            System.gc();
            System.gc();
            var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long thread = Thread.currentThread().threadId();
            int blocks = REPS / BLOCK_REPS;
            long[][][] blockNanos = new long[c][v][blocks];
            long[][][] blockCycles = new long[c][v][blocks];
            double[][][] bytes = new double[c][v][REPS];
            long[][] collections = new long[c][v];
            long[][] collectionMillis = new long[c][v];
            long[][] threadNanos = new long[c][v];
            long[][] processNanos = new long[c][v];
            long[][] totalCycles = new long[c][v];
            for (int rep = 0; rep < REPS; rep++) {
                for (int k = 0; k < c; k++) {
                    for (int slot = 0; slot < v; slot++) {
                        int i = (slot + rep) % v;
                        current.setContextClassLoader(loaders.get(i));
                        long beginCollections = collections();
                        long beginCollectionMillis = collectionMillis();
                        long beginProcess = os.getProcessCpuTime();
                        long beginBytes = threads.getThreadAllocatedBytes(thread);
                        long begin = threads.getCurrentThreadCpuTime();
                        long elapsed;
                        long count = 0;
                        do {
                            cycles[i][k].accept(BATCH);
                            count += BATCH;
                            elapsed = threads.getCurrentThreadCpuTime() - begin;
                        } while (elapsed < REP_CPU_NANOS);
                        long allocated = threads.getThreadAllocatedBytes(thread) - beginBytes;
                        long process = os.getProcessCpuTime() - beginProcess;
                        bytes[k][i][rep] = (double) allocated / count;
                        blockNanos[k][i][rep / BLOCK_REPS] += elapsed;
                        blockCycles[k][i][rep / BLOCK_REPS] += count;
                        collections[k][i] += collections() - beginCollections;
                        collectionMillis[k][i] += collectionMillis() - beginCollectionMillis;
                        threadNanos[k][i] += elapsed;
                        processNanos[k][i] += process;
                        totalCycles[k][i] += count;
                    }
                }
            }
            StringBuilder report = new StringBuilder();
            // CPU clocks tick coarsely, so CPU is summed over blocks of reps; each block is paired with the geometric
            // mean of the two reference loaders' blocks.
            int reference = v > 2 ? 2 : 1;
            for (int k = 0; k < c; k++) {
                report.append(names[k]).append('\n');
                double referenceMean = Math.sqrt(mean(threadNanos[k][0], totalCycles[k][0])
                        * mean(threadNanos[k][reference], totalCycles[k][reference]));
                for (int i = 0; i < v; i++) {
                    double[] block = new double[blocks];
                    double[] ratio = new double[blocks];
                    for (int b = 0; b < blocks; b++) {
                        block[b] = mean(blockNanos[k][i][b], blockCycles[k][i][b]);
                        ratio[b] = block[b]
                                / Math.sqrt(mean(blockNanos[k][0][b], blockCycles[k][0][b])
                                        * mean(blockNanos[k][reference][b], blockCycles[k][reference][b]));
                    }
                    double[] sortedBlock = sorted(block);
                    double[] sortedRatio = sorted(ratio);
                    double threadMean = mean(threadNanos[k][i], totalCycles[k][i]);
                    report.append(String.format(
                            "  %-5s thread %6.0f ns/cycle (blocks %.0f-%.0f), /%s+%s %.3f (blocks median %.3f,"
                                    + " %.3f-%.3f); process %6.0f ns/cycle (other threads %5.0f);"
                                    + " %6.0f B/cycle (min %.0f, max %.0f); %.2f GCs, %.2f GC ms per 100k cycles%n",
                            variants.get(i),
                            threadMean,
                            sortedBlock[0],
                            sortedBlock[blocks - 1],
                            variants.get(0),
                            variants.get(reference),
                            threadMean / referenceMean,
                            sortedRatio[blocks / 2],
                            sortedRatio[0],
                            sortedRatio[blocks - 1],
                            mean(processNanos[k][i], totalCycles[k][i]),
                            mean(processNanos[k][i] - threadNanos[k][i], totalCycles[k][i]),
                            sorted(bytes[k][i])[REPS / 2],
                            sorted(bytes[k][i])[0],
                            sorted(bytes[k][i])[REPS - 1],
                            collections[k][i] * 100_000.0 / totalCycles[k][i],
                            collectionMillis[k][i] * 100_000.0 / totalCycles[k][i]));
                }
            }
            return report.toString();
        } finally {
            for (int i = 0; i < v; i++) {
                current.setContextClassLoader(loaders.get(i));
                if (closers[i] != null) closers[i].close();
            }
            current.setContextClassLoader(context);
        }
    }

    private static long collections() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
            total += Math.max(0, bean.getCollectionCount());
        return total;
    }

    private static long collectionMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
            total += Math.max(0, bean.getCollectionTime());
        return total;
    }

    private static double mean(long nanos, long cycles) {
        return (double) nanos / cycles;
    }

    private static double[] sorted(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted;
    }

    private static URL[] classpath() throws IOException {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++)
            urls[i] = Path.of(entries[i]).toUri().toURL();
        return urls;
    }

    private static ClassLoader loader(URL first, URL[] classpath) {
        List<URL> urls = new ArrayList<>();
        if (first != null) urls.add(first);
        urls.addAll(Arrays.asList(classpath));
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    /** Compiles, at the revision, each main source file that differs from it in the working tree. */
    private static Path compileBase(String revision, String processorPath) throws Exception {
        Path root = Path.of(git(Path.of("").toAbsolutePath(), "rev-parse", "--show-toplevel")
                .trim());
        Path sources = Files.createTempDirectory("proof-bench-src");
        Path classes = Files.createTempDirectory("proof-bench-classes");
        List<String> files = new ArrayList<>();
        for (String name :
                git(root, "diff", "--name-only", revision, "--", "*.java").split("\n")) {
            int main = name.indexOf("/src/main/java/");
            if (main < 0) continue;
            Path source = sources.resolve(name.substring(main + "/src/main/java/".length()));
            Files.createDirectories(source.getParent());
            Files.writeString(source, git(root, "show", revision + ":" + name));
            files.add(source.toString());
        }
        if (files.isEmpty()) throw new AssertionError("no main source differs from " + revision);
        boolean processors = processorPath != null && !processorPath.isEmpty();
        String compileClasspath = System.getProperty("java.class.path");
        if (processors) compileClasspath = processorPath + File.pathSeparator + compileClasspath;
        List<String> args = new ArrayList<>(List.of(
                "-d", classes.toString(), "-cp", compileClasspath, "--release", "21", "-encoding", "UTF-8", "-nowarn"));
        if (processors) args.addAll(List.of("-processorpath", processorPath));
        else args.add("-proc:none");
        args.addAll(files);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int status = javac.run(null, null, null, args.toArray(String[]::new));
        if (status != 0) throw new AssertionError("base compile failed: " + status);
        return classes;
    }

    private static String git(Path directory, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
        return output;
    }

    /** One player's proof-task traffic; loaded once per variant class loader. */
    static final class Workload {
        private static final Field PENDING_SEND = pendingSend();

        private Workload() {}

        static Object[] create(String protocolName) {
            ProtocolVersion protocol = ProtocolVersion.valueOf(protocolName);
            CultPlayer player = player(protocol);
            EmbeddedChannel channel = (EmbeddedChannel) player.user.getChannel();
            channel.freezeTime();
            player.entityID = 7;
            FairReach fairReach = player.checkManager.getListener(FairReach.class);
            fairReach.max_ping_compensation = 100;
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < PLAYERS + MOBS; i++) {
                int id = 1000 + i * 7;
                ids.add(id);
                Vec3 position = new Vec3(i % 11 - 5 + 0.5, 64, i % 13 - 6 + 0.5);
                if (i < PLAYERS) {
                    fairReach.onAddEntity(
                            null,
                            player,
                            new ClientboundAddEntity(
                                    id, "minecraft:player", new Vec3d(position.x, position.y, position.z), 0, 0, 0));
                }
                int type = i < PLAYERS ? EntityTypeIds.PLAYER : MOB_TYPES[i % MOB_TYPES.length];
                player.packetEntityReplication.addEntity(id, type, position, 0, 0, 0, -1);
            }
            acknowledgeAll(player);
            int[] counters = new int[3];
            IntConsumer answered = batch -> {
                for (int b = 0; b < batch; b++) {
                    int n = counters[0]++;
                    ping(player);
                    moves(player, ids, ids.size(), n);
                    acknowledgeAll(player);
                    channel.runScheduledPendingTasks();
                }
            };
            IntConsumer overdue = batch -> {
                for (int b = 0; b < batch; b++) {
                    int n = counters[1]++;
                    ping(player);
                    moves(player, ids, PLAYERS, n);
                    channel.advanceTimeBy(fairReach.max_ping_compensation + 1, TimeUnit.MILLISECONDS);
                    channel.runScheduledPendingTasks();
                    acknowledgeAll(player);
                }
            };
            byte[] flags = {0};
            PacketType<EntityMetadata> metadataType = metadataType();
            IntConsumer data = batch -> {
                for (int b = 0; b < batch; b++) {
                    int n = counters[2]++;
                    ping(player);
                    for (int id : ids) {
                        var packet = new EntityMetadata(
                                id, List.of(EntityMetadata.Entry.of(0, (byte) (n & 1), flags, 0, 1)));
                        player.packetEntityReplication.onSetEntityData(
                                new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, metadataType, packet, false),
                                player,
                                packet);
                    }
                    acknowledgeAll(player);
                }
            };
            String[] names = {
                protocol + " moves answered x" + ids.size(),
                protocol + " player moves past deadline x" + PLAYERS,
                protocol + " entity data x" + ids.size()
            };
            AutoCloseable closer = () -> close(player);
            return new Object[] {names, new IntConsumer[] {answered, overdue, data}, closer};
        }

        private static void moves(CultPlayer player, List<Integer> ids, int count, int n) {
            short dx = (short) ((n & 1) == 0 ? 1024 : -1024);
            boolean rotate = (n & 2) == 0;
            for (int i = 0; i < count; i++) {
                var packet = new ClientboundMoveEntity(
                        ids.get(i),
                        new EntityDelta.Linear(dx, (short) 0, dx),
                        rotate ? 10.0F : -10.0F,
                        0.0F,
                        true,
                        true,
                        rotate);
                player.packetEntityReplication.onMoveEntity(
                        new PacketSendEvent<>(
                                player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, false),
                        player,
                        packet);
            }
        }

        @SuppressWarnings("unchecked")
        private static PacketType<EntityMetadata> metadataType() {
            return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                    .filter(type -> type.recordClass() == EntityMetadata.class)
                    .findFirst()
                    .orElseThrow();
        }

        private static void ping(CultPlayer player) {
            player.lastTransactionSent.set(
                    Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get()) + 1);
        }

        private static void acknowledgeAll(CultPlayer player) {
            int transaction = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
            // Handlers called without a send never flush their pings, so drop them rather than let them pile up.
            try {
                ((Map<?, ?>) PENDING_SEND.get(player)).clear();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }

        private static Field pendingSend() {
            try {
                Field field = CultPlayer.class.getDeclaredField("transactionsPendingSend");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                throw new AssertionError(e);
            }
        }

        private static CultPlayer player(ProtocolVersion protocol) {
            OfflineCultTestBootstrap.installConfig();
            PlatformConnection platform = new PlatformConnection() {
                @Override
                public User.Profile authenticatedProfile() {
                    return null;
                }

                @Override
                public PlayerBinding playerBinding() {
                    return null;
                }

                @Override
                public ProtocolVersion getObservedProtocol() {
                    return protocol;
                }

                @Override
                public ProtocolVersion getClientProtocol() {
                    return protocol;
                }

                @Override
                public void disconnect(Component reason) {}

                @Override
                public void sendMessage(Component message) {}
            };
            User user = TestUsers.create(
                    new User.Profile(UUID.randomUUID(), "Proof_Bench"), new EmbeddedChannel(), platform);
            CultPlayer player = new CultPlayer(user);
            user.bind(platformPlayer());
            return player;
        }

        /** A platform player answering every call with an empty value; a mock maker loads once per JVM. */
        private static PlatformPlayer platformPlayer() {
            return (PlatformPlayer) Proxy.newProxyInstance(
                    PlatformPlayer.class.getClassLoader(),
                    new Class<?>[] {PlatformPlayer.class},
                    (proxy, method, args) -> {
                        Class<?> type = method.getReturnType();
                        if (method.getName().equals("equals")) return proxy == args[0];
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (type == boolean.class) return false;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == double.class) return 0.0D;
                        if (type == float.class) return 0.0F;
                        if (type == short.class) return (short) 0;
                        if (type == byte.class) return (byte) 0;
                        if (type == char.class) return (char) 0;
                        if (type == Optional.class) return Optional.empty();
                        if (type == List.class || type == Collection.class) return List.of();
                        if (type == Set.class) return Set.of();
                        if (type == Map.class) return Map.of();
                        return null;
                    });
        }

        private static void close(CultPlayer player) throws IOException {
            Object channel = player.user.getChannel();
            player.onRemove();
            if (channel instanceof EmbeddedChannel embedded) {
                embedded.runPendingTasks();
                embedded.finishAndReleaseAll();
            }
        }
    }
}
