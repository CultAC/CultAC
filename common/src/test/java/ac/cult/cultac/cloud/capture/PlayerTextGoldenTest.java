package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.IdTable;
import ac.cult.cultac.protocol.data.ProtocolData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Drawn packets of every text-bearing kind on every protocol, from {@code player-text/<protocol>.bin.gz}, and what
 * {@link PlayerText} keeps of each: the same array, nothing, or the bytes given. The packets fill every field a
 * protocol has with what servers and clients write there, text where players write it, stacks inside stacks, inline
 * registry entries, and profiles with skins and without.
 *
 * <p>The goldens pin the stripped output of the recorded packets; regenerate them only when the stripping rules
 * change, with {@code ./gradlew :common:test --tests '*PlayerTextGoldenTest' -Dcultac.updatePlayerTextGoldens=true},
 * which rewrites each record's outcome from the current {@link PlayerText} under {@code src/test/resources}. New
 * protocols and packet kinds are covered by {@link PlayerTextTest}.
 *
 * <p>A record is a byte (1 clientbound, 0 serverbound), the packet id and the packet (a varint length, then its bytes,
 * id first), and its outcome: 0 for the same array, 1 for not sent, 2 then a varint length and the bytes kept.
 */
class PlayerTextGoldenTest {
    /** Kinds covered by {@link PlayerTextTest}'s hand-encoded packets alone. */
    private static final Set<String> WITHOUT_GOLDENS = Set.of("level_chunk_with_light", "level_particles");

    private static final int SAME = 0;
    private static final int DROPPED = 1;
    private static final int CHANGED = 2;

    private static final boolean UPDATE = Boolean.getBoolean("cultac.updatePlayerTextGoldens");

    @Test
    void everyDrawnPacketKeepsWhatItKeptWhenItWasRecorded() throws IOException {
        for (int protocol = 768; protocol <= 777; protocol++) {
            PlayerText text = PlayerText.of(protocol);
            int cases = 0;
            int changed = 0;
            ByteArrayOutputStream updated = new ByteArrayOutputStream();
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(golden(protocol)))) {
                while (in.available() > 0) {
                    boolean clientbound = in.readUnsignedByte() == 1;
                    int id = varInt(in);
                    byte[] packet = in.readNBytes(varInt(in));
                    int outcome = in.readUnsignedByte();
                    String what = protocol + " case " + cases + (clientbound ? " clientbound " : " serverbound ") + id;
                    byte[] kept = strip(text, clientbound, id, packet);
                    if (UPDATE) {
                        if (outcome == CHANGED) in.skipNBytes(varInt(in));
                        record(updated, clientbound, id, packet, kept);
                        if (kept != null && kept != packet) changed++;
                    } else {
                        switch (outcome) {
                            case SAME -> assertSame(packet, kept, what);
                            case DROPPED -> assertNull(kept, what);
                            default -> {
                                assertNotNull(kept, what);
                                assertArrayEquals(in.readNBytes(varInt(in)), kept, what);
                                changed++;
                            }
                        }
                    }
                    assertNull(strip(text, clientbound, id, Arrays.copyOf(packet, packet.length - 1)), what + " cut");
                    assertNull(strip(text, clientbound, id, Arrays.copyOf(packet, packet.length + 1)), what + " long");
                    cases++;
                }
            }
            if (UPDATE) {
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(Path.of(
                        "src/test/resources/ac/cult/cultac/cloud/capture/player-text", protocol + ".bin.gz")))) {
                    updated.writeTo(out);
                }
            }
            assertTrue(cases >= 280, protocol + " has " + cases + " cases");
            assertTrue(changed >= cases / 3, protocol + " stripped " + changed + " of " + cases);
        }
    }

    private static byte[] strip(PlayerText text, boolean clientbound, int id, byte[] packet) {
        return clientbound ? text.clientbound(id, packet, true) : text.serverbound(id, packet, true);
    }

    private static void record(ByteArrayOutputStream out, boolean clientbound, int id, byte[] packet, byte[] kept) {
        out.write(clientbound ? 1 : 0);
        writeVarInt(out, id);
        writeVarInt(out, packet.length);
        out.writeBytes(packet);
        if (kept == packet) {
            out.write(SAME);
        } else if (kept == null) {
            out.write(DROPPED);
        } else {
            out.write(CHANGED);
            writeVarInt(out, kept.length);
            out.writeBytes(kept);
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] golden(int protocol) throws IOException {
        try (InputStream resource =
                PlayerTextGoldenTest.class.getResourceAsStream("player-text/" + protocol + ".bin.gz")) {
            assertNotNull(resource, "goldens for " + protocol);
            try (GZIPInputStream unzipped = new GZIPInputStream(resource)) {
                return unzipped.readAllBytes();
            }
        }
    }

    private static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
    }

    @Test
    void theGoldensCoverEveryPacketThatCanCarryText() throws IOException {
        for (int protocol = 768; protocol <= 777; protocol++) {
            int clientbound = 0;
            int serverbound = 0;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(golden(protocol)))) {
                Set<Integer> ids = new HashSet<>();
                while (in.available() > 0) {
                    boolean isClientbound = in.readUnsignedByte() == 1;
                    int id = varInt(in);
                    in.skipNBytes(varInt(in));
                    if (in.readUnsignedByte() == 2) in.skipNBytes(varInt(in));
                    if (ids.add(isClientbound ? id : -1 - id)) {
                        if (isClientbound) clientbound++;
                        else serverbound++;
                    }
                }
            }
            IdTable ids = ProtocolData.load(ProtocolVersion.of(protocol))
                    .packets(ConnectionPhase.PLAY, PacketDirection.CLIENTBOUND);
            long kinds = PlayerText.CLIENTBOUND.stream()
                    .filter(name -> !WITHOUT_GOLDENS.contains(name) && ids.id("minecraft:" + name) >= 0)
                    .count();
            assertEquals(kinds, clientbound, protocol + " clientbound kinds");
            assertEquals(2, serverbound, protocol + " serverbound kinds");
        }
    }
}
