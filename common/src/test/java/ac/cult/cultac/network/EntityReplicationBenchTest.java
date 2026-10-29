package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundMoveEntity;
import ac.cult.cultac.protocol.value.EntityDelta;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.Vec3;
import com.sun.management.ThreadMXBean;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Thread CPU and allocation of tracked-entity replication through the packet handlers: relative moves proven and
 * ticked, and entity data; runs only with CULT_REPLICATION_BENCH set to an output file.
 */
class EntityReplicationBenchTest {
    private static final int ENTITIES = 60;
    private static final int WARMUP_CYCLES = 20_000;
    private static final int BATCH = 20;
    private static final long REP_CPU_NANOS = 300_000_000L;
    private static final int REPS = 15;
    private static final int[] TYPES = {
        EntityTypeIds.ZOMBIE,
        EntityTypeIds.PLAYER,
        EntityTypeIds.PIG,
        EntityTypeIds.ARMOR_STAND,
        EntityTypeIds.CHICKEN,
        EntityTypeIds.ITEM
    };

    private interface Cycle {
        void run(int n);
    }

    @Test
    void replication() throws Exception {
        String out = System.getenv("CULT_REPLICATION_BENCH");
        assumeTrue(out != null && !out.isEmpty());
        CultPlayer player = player();
        try {
            player.entityID = 7;
            Random random = new Random(11);
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < ENTITIES; i++) {
                int id = 1000 + random.nextInt(1_000_000);
                while (ids.contains(id)) id++;
                ids.add(id);
                player.packetEntityReplication.addEntity(
                        id,
                        TYPES[i % TYPES.length],
                        new Vec3(random.nextInt(31) - 15 + 0.5, 64, random.nextInt(31) - 15 + 0.5),
                        0,
                        0,
                        0,
                        -1);
            }
            acknowledgeAll(player);
            Cycle moves = n -> {
                short dx = (short) ((n & 1) == 0 ? 1024 : -1024);
                boolean rotate = (n & 2) == 0;
                for (int i = 0; i < ids.size(); i++) {
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
                acknowledgeAll(player);
                for (PacketEntity entity : player.compensatedEntities.entityMap.values())
                    entity.onMovement(player);
            };
            byte[] flags = {0};
            PacketType<EntityMetadata> metadataType = metadataType();
            Cycle data = n -> {
                for (int i = 0; i < ids.size(); i++) {
                    var packet = new EntityMetadata(
                            ids.get(i), List.of(EntityMetadata.Entry.of(0, (byte) (n & 1), flags, 0, 1)));
                    player.packetEntityReplication.onSetEntityData(
                            new PacketSendEvent<>(
                                    player.user, ConnectionPhase.PLAY, metadataType, packet, false),
                            player,
                            packet);
                }
                acknowledgeAll(player);
            };
            String[] names = {"relative moves x" + ENTITIES, "entity data x" + ENTITIES};
            Cycle[] cycles = {moves, data};
            var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().threadId();
            int[] counters = new int[cycles.length];
            for (int c = 0; c < cycles.length; c++) {
                for (int i = 0; i < WARMUP_CYCLES; i++) cycles[c].run(counters[c]++);
            }
            double[][] cpu = new double[cycles.length][REPS];
            double[][] bytes = new double[cycles.length][REPS];
            for (int rep = 0; rep < REPS; rep++) {
                for (int c = 0; c < cycles.length; c++) {
                    long beginBytes = bean.getThreadAllocatedBytes(thread);
                    long begin = bean.getCurrentThreadCpuTime();
                    long elapsed;
                    long count = 0;
                    do {
                        for (int i = 0; i < BATCH; i++) cycles[c].run(counters[c]++);
                        count += BATCH;
                        elapsed = bean.getCurrentThreadCpuTime() - begin;
                    } while (elapsed < REP_CPU_NANOS);
                    bytes[c][rep] = (double) (bean.getThreadAllocatedBytes(thread) - beginBytes) / count;
                    cpu[c][rep] = (double) elapsed / count;
                }
            }
            StringBuilder report = new StringBuilder();
            int sentBefore = player.lastTransactionSent.get();
            moves.run(0);
            report.append("transactions per move cycle: " + (player.lastTransactionSent.get() - sentBefore) + "\n");
            for (int c = 0; c < cycles.length; c++) {
                double[] sortedCpu = cpu[c].clone();
                double[] sortedBytes = bytes[c].clone();
                Arrays.sort(sortedCpu);
                Arrays.sort(sortedBytes);
                report.append(String.format(
                        "%s: %.0f B/cycle (min %.0f, max %.0f), median %.0f ns/cycle (min %.0f, max %.0f)%n",
                        names[c],
                        sortedBytes[REPS / 2],
                        sortedBytes[0],
                        sortedBytes[REPS - 1],
                        sortedCpu[REPS / 2],
                        sortedCpu[0],
                        sortedCpu[REPS - 1]));
            }
            System.out.print(report);
            Files.writeString(
                    Path.of(out), report + "---\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally {
            close(player);
        }
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static void acknowledgeAll(CultPlayer player) {
        int transaction = Math.max(player.lastTransactionSent.get(), player.lastTransactionReceived.get());
        player.lastTransactionReceived.set(transaction);
        player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static CultPlayer player() {
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
        User user = TestUsers.create(
                new User.Profile(UUID.randomUUID(), "Replication"), new EmbeddedChannel(), platform);
        CultPlayer player = new CultPlayer(user);
        user.bind(Mockito.mock(PlatformPlayer.class));
        return player;
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
