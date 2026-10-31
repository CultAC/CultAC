package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.combat.FairReach;
import ac.cult.cultac.events.packets.PacketEntityReplication;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundAddEntity;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.TrackerData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.reload.api.HostContext;
import ac.cult.reload.host.ScopedResources;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Packet-handler, proof-callback, interpolation and FairReach deadline cost for one observer tracking 200 moving
 * players. Set CULT_SUSTAINED_REPLICATION_BENCH to a CSV file; normal test runs skip this benchmark. Each fixed wave
 * represents 50 ms, uses an existing shared proof or a pre-wave transaction, and acknowledges n followed by n+1
 * after 100 ms. A separate 400 ms acknowledgement case exercises FairReach's 300 ms deadline winning first.
 * Deadline callbacks enter the actual ScopedResources admission path through a concrete, non-mocked host.
 * This excludes encoder serialization, socket I/O, ping generation and inbound pong decoding. Run the identical
 * source against both production revisions, in alternating fresh JVMs; report thread CPU, not elapsed wall time.
 */
class SustainedEntityReplicationBenchTest {
    private static final int ENTITIES = 200;
    private static final int WARMUP_WAVES = Integer.getInteger("cult.replication.bench.warmupWaves", 2_000);
    private static final int MEASURED_WAVES = Integer.getInteger("cult.replication.bench.measuredWaves", 2_000);
    private static final int REPS = Integer.getInteger("cult.replication.bench.reps", 5);
    private static volatile long blackhole;

    private enum Movement {
        POSITION(true, false),
        POSITION_ROTATION(true, true),
        ROTATION(false, true);

        private final boolean position;
        private final boolean rotation;

        Movement(boolean position, boolean rotation) {
            this.position = position;
            this.rotation = rotation;
        }
    }

    @Test
    void sustainedReplication() throws Exception {
        String output = System.getenv("CULT_SUSTAINED_REPLICATION_BENCH");
        assumeTrue(output != null && !output.isEmpty());
        run(Path.of(output));
    }

    /** Allows a resolved test runtime classpath to launch independent benchmark forks without another Gradle build. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output CSV path");
        run(Path.of(args[0]));
    }

    private static void run(Path output) throws Exception {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported() || !bean.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("The benchmark requires thread CPU and allocation counters");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        bean.setThreadCpuTimeEnabled(true);
        long thread = Thread.currentThread().threadId();
        StringBuilder report = new StringBuilder();
        report.append("# java=")
                .append(System.getProperty("java.runtime.version"))
                .append(", vm=")
                .append(System.getProperty("java.vm.name"))
                .append(", processors=")
                .append(Runtime.getRuntime().availableProcessors())
                .append('\n');
        report.append("scenario,rep,movements,thread_cpu_ns,allocated_bytes,checksum\n");
        for (boolean bundled : new boolean[] {true, false}) {
            for (Movement movement : Movement.values()) {
                try (Fixture fixture = new Fixture(bundled, movement, 2)) {
                    fixture.waves(WARMUP_WAVES);
                    for (int rep = 0; rep < REPS; rep++) {
                        long bytesBefore = bean.getThreadAllocatedBytes(thread);
                        long cpuBefore = bean.getCurrentThreadCpuTime();
                        fixture.waves(MEASURED_WAVES);
                        long cpu = bean.getCurrentThreadCpuTime() - cpuBefore;
                        long bytes = bean.getThreadAllocatedBytes(thread) - bytesBefore;
                        long checksum = fixture.checksum();
                        blackhole = checksum;
                        report.append(String.format(
                                Locale.ROOT,
                                "%s_%s_ack100,%d,%d,%d,%d,%d%n",
                                bundled ? "bundled" : "unbundled",
                                movement.name().toLowerCase(Locale.ROOT),
                                rep,
                                ENTITIES * MEASURED_WAVES,
                                cpu,
                                bytes,
                                checksum));
                    }
                    fixture.drain();
                }
            }
        }
        try (Fixture fixture = new Fixture(true, Movement.POSITION_ROTATION, 8)) {
            fixture.waves(WARMUP_WAVES);
            for (int rep = 0; rep < REPS; rep++) {
                long bytesBefore = bean.getThreadAllocatedBytes(thread);
                long cpuBefore = bean.getCurrentThreadCpuTime();
                fixture.waves(MEASURED_WAVES);
                long cpu = bean.getCurrentThreadCpuTime() - cpuBefore;
                long bytes = bean.getThreadAllocatedBytes(thread) - bytesBefore;
                long checksum = fixture.checksum();
                blackhole = checksum;
                report.append(String.format(
                        Locale.ROOT,
                        "bundled_position_rotation_ack400,%d,%d,%d,%d,%d%n",
                        rep,
                        ENTITIES * MEASURED_WAVES,
                        cpu,
                        bytes,
                        checksum));
            }
            fixture.drain();
        }
        System.out.print(report);
        Files.writeString(output, report, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static final class Fixture implements AutoCloseable {
        private final CultPlayer player;
        private final EmbeddedChannel channel;
        private final boolean bundled;
        private final Movement movement;
        private final int acknowledgementWaves;
        private final Field contextField;
        private final Object previousContext;
        private final ScopedResources resources = new ScopedResources(getClass().getClassLoader());
        private final PacketEntity[] entities = new PacketEntity[ENTITIES];
        private int wave;

        private Fixture(boolean bundled, Movement movement, int acknowledgementWaves) throws Exception {
            this.bundled = bundled;
            this.movement = movement;
            this.acknowledgementWaves = acknowledgementWaves;
            OfflineCultTestBootstrap.installConfig();
            channel = new EmbeddedChannel();
            channel.freezeTime();
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
                    return ProtocolVersion.V1_21_11;
                }

                @Override
                public ProtocolVersion getClientProtocol() {
                    return ProtocolVersion.V1_21_11;
                }

                @Override
                public void disconnect(Component reason) {}

                @Override
                public void sendMessage(Component message) {}
            };
            User user = TestUsers.create(new User.Profile(new UUID(0, 1), "ReplicationBench"), channel, platform);
            player = new CultPlayer(user);
            user.bind(Mockito.mock(PlatformPlayer.class, Mockito.withSettings().stubOnly()));
            player.entityID = 7;
            player.x = 0;
            player.y = 64;
            player.z = 0;
            Field delimiter = PacketEntityReplication.class.getDeclaredField("useReachBundleDelimiter");
            delimiter.setAccessible(true);
            delimiter.setBoolean(player.packetEntityReplication, bundled);
            FairReach fairReach = player.checkManager.getListener(FairReach.class);
            for (int i = 0; i < ENTITIES; i++) {
                int id = id(i);
                Vec3d position = new Vec3d(initialX(i), 64, initialZ(i));
                fairReach.onAddEntity(
                        null, player, new ClientboundAddEntity(id, "minecraft:player", position, 0, 0, 0));
                player.packetEntityReplication.addEntity(
                        id, EntityTypeIds.PLAYER, new Vec3(position.x(), position.y(), position.z()), 0, 0, 0, -1);
                entities[i] = player.compensatedEntities.getEntity(id);
            }
            contextField = RuntimeEnvironment.class.getDeclaredField("context");
            contextField.setAccessible(true);
            previousContext = contextField.get(null);
            contextField.set(null, new BenchmarkHost(resources));
        }

        private void waves(int count) {
            for (int n = 0; n < count; n++, wave++) {
                int first = wave * 2 + 1;
                player.lastTransactionSent.set(first);
                short delta = (short) ((wave & 1) == 0 ? 1024 : -1024);
                float yaw = (wave & 1) == 0 ? 15 : -15;
                for (int i = 0; i < ENTITIES; i++) {
                    var packet = new ClientboundMoveEntity(
                            id(i),
                            new EntityDelta.Linear(
                                    movement.position ? delta : (short) 0,
                                    (short) 0,
                                    movement.position ? delta : (short) 0),
                            yaw,
                            0,
                            true,
                            movement.position,
                            movement.rotation);
                    var event = new PacketSendEvent<>(
                            player.user, ConnectionPhase.PLAY, ClientboundPackets.MOVE_ENTITY, packet, bundled);
                    // An existing encoder/listener proof: no fake per-entity ping allocation in this handler benchmark.
                    if (bundled) event.receiptProof(first);
                    player.packetEntityReplication.onMoveEntity(event, player, packet);
                }
                player.lastTransactionSent.set(first + 1);
                channel.advanceTimeBy(50, TimeUnit.MILLISECONDS);
                int acknowledgedWave = wave + 1 - acknowledgementWaves;
                if (acknowledgedWave >= 0) {
                    acknowledge(acknowledgedWave * 2 + 1);
                    acknowledge(acknowledgedWave * 2 + 2);
                }
                for (PacketEntity entity : entities) entity.onMovement(player, true);
                channel.runScheduledPendingTasks();
                channel.runPendingTasks();
            }
        }

        private void acknowledge(int transaction) {
            player.lastTransactionReceived.set(transaction);
            player.latencyUtils.handleNettySyncTransaction(transaction);
        }

        private long checksum() {
            long result = 1;
            for (int i = 0; i < ENTITIES; i++) {
                TrackerData data = player.compensatedEntities.getTrackedEntity(id(i));
                assertEquals(initialX(i), data.getX());
                assertEquals(64, data.getY());
                assertEquals(initialZ(i), data.getZ());
                assertNull(entities[i].oldPacketLocation, "Every movement must have settled by n+1");
                result = 31 * result + Double.doubleToLongBits(data.getX());
                result = 31 * result + Double.doubleToLongBits(data.getY());
                result = 31 * result + Double.doubleToLongBits(data.getZ());
                result = 31 * result + Float.floatToIntBits(data.getXRot());
                result = 31 * result + Float.floatToIntBits(data.getYRot());
                SimpleCollisionBox box = entities[i].getPossibleMovementCollisionBoxes();
                result = 31 * result + Double.doubleToLongBits(box.minX);
                result = 31 * result + Double.doubleToLongBits(box.minY);
                result = 31 * result + Double.doubleToLongBits(box.minZ);
                result = 31 * result + Double.doubleToLongBits(box.maxX);
                result = 31 * result + Double.doubleToLongBits(box.maxY);
                result = 31 * result + Double.doubleToLongBits(box.maxZ);
            }
            assertEquals(wave * 2, player.lastTransactionSent.get());
            assertEquals((wave + 1 - acknowledgementWaves) * 2, player.lastTransactionReceived.get());
            return result;
        }

        private void drain() {
            acknowledge(player.lastTransactionSent.get());
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            for (PacketEntity entity : entities) assertNull(entity.oldPacketLocation);
            assertEquals(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
        }

        private static int id(int i) {
            return 10_000 + i * 17;
        }

        private static double initialX(int i) {
            return i % 20 + 0.5;
        }

        private static double initialZ(int i) {
            return i / 20 + 0.5;
        }

        @Override
        public void close() {
            try {
                assertEquals(0, resources.inFlight(), "Every admitted deadline must release its runtime lease");
            } finally {
                try {
                    contextField.set(null, previousContext);
                } catch (IllegalAccessException exception) {
                    throw new AssertionError(exception);
                }
                resources.close().toCompletableFuture().join();
                player.onRemove();
                channel.runPendingTasks();
                channel.finishAndReleaseAll();
            }
        }
    }

    /** The resource scope is real; unused host operations do not introduce mock invocation cost into each callback. */
    private record BenchmarkHost(ScopedResources resources) implements HostContext {
        @Override
        public String generationId() {
            return "benchmark";
        }

        @Override
        public String platform() {
            return "bukkit";
        }

        @Override
        public String build() {
            return "benchmark";
        }

        @Override
        public Path directory() {
            return Path.of(".");
        }

        @Override
        public Object plugin() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object server() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object logger() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean current() {
            return true;
        }

        @Override
        public boolean owns(Object session) {
            return true;
        }

        @Override
        public boolean pin(Object session) {
            return true;
        }

        @Override
        public void release(Object session, CompletionStage<Void> cleanup) {}

        @Override
        public String ownerOf(Object connection) {
            return generationId();
        }

        @Override
        public void bindUser(Object connection, UUID uuid, Object nativePlayer, Object user) {}

        @Override
        public void unbindUser(Object connection) {}

        @Override
        public Object shared(String key, Supplier<Object> create) {
            return create.get();
        }

        @Override
        public Object publicApi() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object wrap(Object value) {
            return value;
        }

        @Override
        public Object wrap(Object value, Object user) {
            return value;
        }

        @Override
        public CompletionStage<String> update(String filename) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> status() {
            return List.of();
        }
    }
}
