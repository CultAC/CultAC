package ac.cult.cultac.network.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaPacketLoggerRecordingTest {
    @TempDir
    Path directory;

    /** Packets are observed exactly while a capture records, across the first start, a stop and a restart. */
    @Test
    void observesExactlyWhileACaptureRecords() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        List<String> feedback = new CopyOnWriteArrayList<>();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Logged"), new EmbeddedChannel());
        CultConnection connection = user.getCultConnection();
        User other = TestUsers.create(new User.Profile(UUID.randomUUID(), "Unlogged"), new EmbeddedChannel());
        JavaPacketLogger logger = new JavaPacketLogger(directory, "test", "test", feedback::add);
        try {
            PacketLogObserver observer = logger.observer(connection);
            PacketLogObserver otherObserver = logger.observer(other.getCultConnection());
            ByteBuf frame = unreadableFrame(connection);
            try {
                assertFalse(observer.active());
                send(observer, frame, 3);

                captureCycle(logger, connection, observer, otherObserver, frame, 5, feedback, 1);
                captureCycle(logger, connection, observer, otherObserver, frame, 4, feedback, 2);
                List<String> logs = logs();
                assertEquals(2, logs.size());
                assertTrue(logs.stream().anyMatch(log -> log.contains(" skipped_unreadable=5")), logs::toString);
                assertTrue(logs.stream().anyMatch(log -> log.contains(" skipped_unreadable=4")), logs::toString);
            } finally {
                frame.release();
            }
        } finally {
            logger.close();
            ((EmbeddedChannel) user.getChannel()).finishAndReleaseAll();
            ((EmbeddedChannel) other.getChannel()).finishAndReleaseAll();
        }
    }

    private static void captureCycle(
            JavaPacketLogger logger,
            CultConnection connection,
            PacketLogObserver observer,
            PacketLogObserver otherObserver,
            ByteBuf frame,
            int packets,
            List<String> reports,
            int saved)
            throws Exception {
        logger.toggle(connection, ignored -> {});
        assertTrue(observer.active());
        assertFalse(otherObserver.active());
        send(observer, frame, packets);
        send(otherObserver, frame, 7);
        logger.toggle(connection, ignored -> {});
        assertFalse(observer.active());
        send(observer, frame, 2);
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (reports.stream()
                        .filter(report -> report.startsWith("Saved Java packet log"))
                        .count()
                < saved) {
            if (System.nanoTime() > deadline) throw new AssertionError("capture did not finish: " + reports);
            Thread.sleep(20);
        }
        send(observer, frame, 2);
    }

    private static void send(PacketLogObserver observer, ByteBuf frame, int count) {
        for (int i = 0; i < count; i++)
            observer.packet(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY, frame.duplicate());
    }

    /** A frame whose id is past every clientbound play id, which a recording capture counts as unreadable. */
    private static ByteBuf unreadableFrame(CultConnection connection) {
        int id = connection
                .runtime()
                .data()
                .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND)
                .size();
        ByteBuf frame = Unpooled.buffer();
        Wire.writeVarInt(frame, id);
        return frame;
    }

    private List<String> logs() throws Exception {
        List<String> logs = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) logs.add(Files.readString(file));
        }
        return logs;
    }
}
