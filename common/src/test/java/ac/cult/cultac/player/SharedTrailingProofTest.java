package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.network.CultWrite;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundSetCamera;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A trailing proof that shares the flush bundle's ping is the proof a fresh record of that ping is. */
class SharedTrailingProofTest {
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    static long sink;

    @Test
    void sharedProofsEqualFreshOnesForEveryPing() {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            int sent = player.lastTransactionSent.get();
            int[] shared = {41, 41, 42, 7, 7, 41, 42, 42, 0x7fffffff};
            for (int i = 0; i < shared.length; i++) {
                var event = event(player, i);
                event.sharedPing(shared[i]);
                List<CultWrite> before =
                        i % 2 == 0 ? List.of() : List.of(new CultWrite(new ClientboundPing(900 + i), false));
                var proof = player.appendTrailingProofTransaction(event, before);
                assertEquals(new CultPlayer.TrackedTransaction(shared[i], null), proof);
                assertEquals(shared[i], proof.transaction());
                assertNull(proof.packet());
                assertEquals(before, event.getWritesAfterSend());
                assertTrue(event.getWritesBeforeSend().isEmpty());
                assertTrue(event.getTasksAfterSend().isEmpty());
                player.markTrackedTransactionPacketSent(proof);
            }
            assertEquals(sent, player.lastTransactionSent.get());
        } finally {
            OfflinePlayers.close(player);
        }
    }

    /**
     * Heap allocation and thread CPU of the shared branch. The base path alternates two pings, so every call builds
     * its proof as before; CULT_SEND_BENCH=1 enables it.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "CULT_SEND_BENCH", matches = "1")
    void sharedProofBench() throws Exception {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        player.user.bind(mock(PlatformPlayer.class));
        try {
            var event = event(player, 1);
            int ops = 5_000_000;
            for (int warm = 0; warm < 5; warm++) {
                run(player, event, ops, true);
                run(player, event, ops, false);
            }
            int reps = 9;
            double[][] bytes = new double[2][reps], cpu = new double[2][reps];
            for (int rep = 0; rep < reps; rep++) {
                for (int k = 0; k < 2; k++) {
                    int which = (rep + k) % 2;
                    long allocated = THREADS.getCurrentThreadAllocatedBytes();
                    long start = THREADS.getCurrentThreadCpuTime();
                    run(player, event, ops, which == 0);
                    cpu[which][rep] = (THREADS.getCurrentThreadCpuTime() - start) / (double) ops;
                    bytes[which][rep] = (THREADS.getCurrentThreadAllocatedBytes() - allocated) / (double) ops;
                }
            }
            report("shared trailing proof base bytes/op", bytes[0]);
            report("shared trailing proof branch bytes/op", bytes[1]);
            report("shared trailing proof base cpu ns/op", cpu[0]);
            report("shared trailing proof branch cpu ns/op", cpu[1]);
        } finally {
            OfflinePlayers.close(player);
        }
    }

    private static void run(CultPlayer player, PacketSendEvent<?> event, int ops, boolean alternate) {
        for (int i = 0; i < ops; i++) {
            event.sharedPing(alternate ? 41 + (i & 1) : 41);
            sink += player.appendTrailingProofTransaction(event, List.of()).transaction();
        }
    }

    private static PacketSendEvent<ClientboundSetCamera> event(CultPlayer player, int entityId) {
        return new PacketSendEvent<>(
                player.user,
                ConnectionPhase.PLAY,
                ClientboundPackets.SET_CAMERA,
                new ClientboundSetCamera(entityId),
                false);
    }

    private static void report(String label, double[] samples) throws Exception {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        String line = String.format(
                        Locale.ROOT,
                        "%s: median %.2f, min %.2f, max %.2f",
                        label,
                        sorted[sorted.length / 2],
                        sorted[0],
                        sorted[sorted.length - 1])
                + System.lineSeparator();
        System.out.print(line);
        String out = System.getenv("CULT_BENCH_OUT");
        if (out != null)
            Files.writeString(
                    Path.of(out), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
