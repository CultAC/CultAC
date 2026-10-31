package ac.cult.cultac.cloud.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerText} keeps the same of every packet read with one stream's {@link PlayerText.Reader} as read afresh:
 * the drawn packets of every protocol interleaved, cut, lengthened and corrupted.
 */
class PlayerTextReaderTest {
    private record Case(PlayerText text, boolean clientbound, int id, byte[] packet) {}

    @Test
    void aReaderKeepsWhatAFreshReadKeeps() throws IOException {
        List<List<Case>> protocols = new ArrayList<>();
        for (int protocol = 768; protocol <= 777; protocol++) protocols.add(cases(protocol));
        PlayerText.TeamNames freshTeams = new PlayerText.TeamNames(new byte[32]);
        PlayerText.TeamNames readerTeams = new PlayerText.TeamNames(new byte[32]);
        PlayerText.Reader reader = new PlayerText.Reader();
        Random random = new Random(7);
        int compared = 0;
        for (int index = 0; ; index++) {
            boolean any = false;
            for (List<Case> cases : protocols) {
                if (index >= cases.size()) continue;
                any = true;
                Case drawn = cases.get(index);
                byte[] packet = drawn.packet();
                byte[] corrupted = packet.clone();
                corrupted[random.nextInt(corrupted.length)] ^= (byte) (1 << random.nextInt(8));
                for (byte[] variant : List.of(
                        packet,
                        Arrays.copyOf(packet, packet.length - 1),
                        Arrays.copyOf(packet, packet.length + 1),
                        Arrays.copyOf(packet, random.nextInt(packet.length)),
                        corrupted)) {
                    byte[] fresh = strip(drawn, variant, freshTeams, null);
                    byte[] read = strip(drawn, variant, readerTeams, reader);
                    String what = drawn.text() + " " + drawn.id() + " case " + index + " length " + variant.length;
                    if (fresh == null) {
                        assertNull(read, what);
                    } else if (fresh == variant) {
                        assertSame(variant, read, what);
                    } else {
                        assertNotNull(read, what);
                        assertTrue(read != variant, what);
                        assertArrayEquals(fresh, read, what);
                    }
                    compared++;
                }
            }
            if (!any) break;
        }
        assertTrue(compared > 10_000, "compared " + compared);
    }

    private static byte[] strip(Case drawn, byte[] packet, PlayerText.TeamNames teams, PlayerText.Reader reader) {
        return drawn.clientbound()
                ? drawn.text().clientbound(drawn.id(), packet, true, teams, reader)
                : drawn.text().serverbound(drawn.id(), packet, true, reader);
    }

    private static List<Case> cases(int protocol) throws IOException {
        PlayerText text = PlayerText.of(protocol);
        List<Case> cases = new ArrayList<>();
        try (InputStream resource =
                        PlayerTextReaderTest.class.getResourceAsStream("player-text/" + protocol + ".bin.gz");
                DataInputStream in =
                        new DataInputStream(new ByteArrayInputStream(new GZIPInputStream(resource).readAllBytes()))) {
            while (in.available() > 0) {
                boolean clientbound = in.readUnsignedByte() == 1;
                int id = varInt(in);
                byte[] packet = in.readNBytes(varInt(in));
                if (in.readUnsignedByte() == 2) in.skipNBytes(varInt(in));
                cases.add(new Case(text, clientbound, id, packet));
            }
        }
        return cases;
    }

    private static int varInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) return value;
        }
    }
}
