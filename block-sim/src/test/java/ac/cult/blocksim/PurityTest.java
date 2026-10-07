package ac.cult.blocksim;

import ac.cult.blocksim.data.DataTables;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurityTest {
    private static final List<String> FORBIDDEN = List.of("net/minecraft/", "org/bukkit/", "io/papermc/", "com/mojang/", "io/netty/", "org/geysermc/");

    @Test void everyRuntimeClassHasOnlyPureJavaReferences() throws Exception {
        Path root = Path.of(DataTables.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var paths = Files.walk(root)) {
            List<Path> classes = paths.filter(p -> p.toString().endsWith(".class")).toList();
            assertFalse(classes.isEmpty());
            for (Path file : classes) assertEquals(List.of(), forbiddenReferences(Files.readAllBytes(file)), file.toString());
        }
    }

    @Test void detectorRejectsClassesAndDescriptorsButAllowsSourceCitations() throws Exception {
        // Runtime source citations are string constants; they are not class dependencies.
        assertFalse(forbiddenDescriptor("world/level/Level.java"));
        assertTrue(forbiddenDescriptor("Lnet/minecraft/world/level/Level;"));
        assertTrue(forbiddenDescriptor("[Lorg/bukkit/World;"));
    }

    static List<String> forbiddenReferences(byte[] bytes) throws Exception {
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(0xcafebabe, in.readInt());
        in.readUnsignedShort(); in.readUnsignedShort();
        int size = in.readUnsignedShort();
        String[] utf8 = new String[size];
        List<Integer> classNames = new ArrayList<>();
        List<Integer> descriptors = new ArrayList<>();
        for (int i = 1; i < size; i++) {
            switch (in.readUnsignedByte()) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipNBytes(4);
                case 5, 6 -> { in.skipNBytes(8); i++; }
                case 7 -> classNames.add(in.readUnsignedShort());
                case 8, 19, 20 -> in.skipNBytes(2);
                case 9, 10, 11, 17, 18 -> in.skipNBytes(4);
                case 12 -> { in.readUnsignedShort(); descriptors.add(in.readUnsignedShort()); }
                case 15 -> in.skipNBytes(3);
                case 16 -> descriptors.add(in.readUnsignedShort());
                default -> throw new AssertionError("Invalid class constant pool");
            }
        }
        List<String> bad = new ArrayList<>();
        for (int index : classNames) if (FORBIDDEN.stream().anyMatch(p -> utf8[index].startsWith(p)) || forbiddenDescriptor(utf8[index])) bad.add(utf8[index]);
        for (String entry : utf8) if (entry != null && forbiddenDescriptor(entry)) bad.add(entry);
        return bad;
    }
    private static boolean forbiddenDescriptor(String descriptor) {
        return FORBIDDEN.stream().anyMatch(prefix -> descriptor.contains("L" + prefix));
    }
}
