package ac.cult.cultac.network;

import static org.mockito.Mockito.mock;

import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketListenerPriority;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundEntityMotion;
import ac.cult.cultac.protocol.value.Vec3d;
import com.sun.management.ThreadMXBean;
import io.netty.util.ReferenceCountUtil;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

/**
 * Bytes and thread CPU per tracked transaction round trip, from the decision to send to its answer: a flush proof
 * leading an entity update through the encoder, a deferred ping marked written by hand, and entity data applied at
 * its proof. Only public API, so the same file runs on any tree. CULT_TXN_BENCH=1 enables it.
 */
@EnabledIfEnvironmentVariable(named = "CULT_TXN_BENCH", matches = "1")
class TransactionRoundTripBenchTest {
    private static final int REPS = 9;
    private static final int IN_FLIGHT = 8;
    private static final PacketType<EntityMetadata> METADATA = metadataType();
    static long sink;

    interface Op {
        void run();
    }

    @Test
    void roundTrips() throws Exception {
        try (var f = RecordReceiveFixture.olderHost(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            f.player.nettyScheduler.removeScheduler();
            f.channel.runPendingTasks();
            f.registrar.register(routes -> {
                var pings = new PacketPingListener();
                routes.send(ClientboundPackets.PING, PacketListenerPriority.LOW, pings::onPing);
                routes.send(
                        ClientboundPackets.ENTITY_MOTION,
                        PacketListenerPriority.LOW,
                        (event, player, packet) -> player.appendFlushProofTransaction(event));
            });
            CultPlayer player = f.player;
            player.recordProvenance(true);
            player.keepAliveProcessor.lastKeepAlivePing = 1L;
            var outstanding = new ArrayDeque<Integer>();
            var motion = new CultWrite(new ClientboundEntityMotion(-100, Vec3d.ZERO), false);
            Op flushLead = () -> {
                f.channel.write(motion);
                f.channel.flush();
                Object out;
                while ((out = f.channel.readOutbound()) != null) ReferenceCountUtil.release(out);
                answer(player, outstanding, player.lastTransactionSent.get());
            };
            Op deferred = () -> {
                var transaction = player.createTrackedTransactionPacketForDeferredSend();
                player.markTrackedTransactionPacketSent(transaction);
                answer(player, outstanding, transaction.transaction());
            };
            var metadata = new EntityMetadata(4321, List.of());
            Op entityData = () -> {
                var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, METADATA, metadata, false);
                player.packetEntityReplication.onSetEntityData(event, player, metadata);
                event.tasksAfter().forEach(Runnable::run);
                answer(player, outstanding, player.lastTransactionSent.get());
            };
            for (int round = 0; round < 2; round++) {
                measure("flush-lead-through-encoder", flushLead, 20_000, f);
                measure("deferred-ping-marked", deferred, 1_000_000, f);
                measure("entity-data-at-proof", entityData, 400_000, f);
            }
        }
    }

    private static void answer(CultPlayer player, ArrayDeque<Integer> outstanding, int transaction) {
        Integer id = player.wireIdOf(transaction);
        if (id != null) outstanding.add(id);
        while (outstanding.size() > IN_FLIGHT) sink += player.addTransactionResponse(outstanding.poll()) ? 1 : 0;
    }

    /** Clears the platform mock's recorded calls between repetitions, outside the measured window. */
    private static void measure(String name, Op op, int ops, RecordReceiveFixture f) throws Exception {
        for (int warm = 0; warm < 3; warm++) {
            Mockito.clearInvocations(f.connection.platform());
            for (int i = 0; i < ops; i++) op.run();
        }
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        double[] bytes = new double[REPS];
        double[] nanos = new double[REPS];
        for (int rep = 0; rep < REPS; rep++) {
            Mockito.clearInvocations(f.connection.platform());
            long allocated = bean.getThreadAllocatedBytes(thread);
            long cpu = bean.getCurrentThreadCpuTime();
            for (int i = 0; i < ops; i++) op.run();
            nanos[rep] = (bean.getCurrentThreadCpuTime() - cpu) / (double) ops;
            bytes[rep] = (bean.getThreadAllocatedBytes(thread) - allocated) / (double) ops;
        }
        String line = String.format(
                "%s B/op median=%.1f min=%.1f max=%.1f ns/op median=%.1f min=%.1f max=%.1f%n",
                name,
                median(bytes),
                Arrays.stream(bytes).min().orElse(0),
                Arrays.stream(bytes).max().orElse(0),
                median(nanos),
                Arrays.stream(nanos).min().orElse(0),
                Arrays.stream(nanos).max().orElse(0));
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }
}
