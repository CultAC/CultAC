package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Structural gate over every shipped class in the shared engine and its proxy/packet modules. */
class EnginePurityTest {
    private static final List<String> FORBIDDEN = List.of("net/minecraft/", "com/mojang/", "org/objectweb/asm/");

    @Test
    void sharedEngineUsesOwnedTypesAndNeverDefinesClasses() throws Exception {
        for (String directory : System.getProperty("cult.pureClassDirectories").split(java.io.File.pathSeparator)) {
            Path root = Path.of(directory);
            try (var paths = Files.walk(root)) {
                var classes =
                        paths.filter(path -> path.toString().endsWith(".class")).toList();
                assertFalse(classes.isEmpty(), "Missing module classes: " + root);
                for (Path file : classes)
                    assertEquals(List.of(), forbiddenReferences(Files.readAllBytes(file)), file.toString());
            }
        }
    }

    private static List<String> forbiddenReferences(byte[] bytes) throws Exception {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(0xcafebabe, input.readInt());
        input.readUnsignedShort();
        input.readUnsignedShort();
        int count = input.readUnsignedShort();
        var strings = new String[count];
        var names = new ArrayList<Integer>();
        var methodNames = new ArrayList<Integer>();
        for (int i = 1; i < count; i++) {
            switch (input.readUnsignedByte()) {
                case 1 -> strings[i] = input.readUTF();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> {
                    input.skipNBytes(8);
                    i++;
                }
                case 7 -> names.add(input.readUnsignedShort());
                case 8, 16, 19, 20 -> input.skipNBytes(2);
                case 9, 10, 11, 17, 18 -> input.skipNBytes(4);
                case 12 -> {
                    methodNames.add(input.readUnsignedShort());
                    input.readUnsignedShort();
                }
                case 15 -> input.skipNBytes(3);
                default -> throw new AssertionError("Invalid class constant pool");
            }
        }
        var bad = new ArrayList<String>();
        for (int index : names) if (FORBIDDEN.stream().anyMatch(strings[index]::startsWith)) bad.add(strings[index]);
        // Descriptors include generic field/method signatures. Source citations
        // and generated class-family names are ordinary strings, not dependencies.
        for (String value : strings)
            if (value != null && FORBIDDEN.stream().anyMatch(prefix -> value.contains("L" + prefix))) bad.add(value);
        for (int index : methodNames) if (strings[index].equals("defineClass")) bad.add("defineClass");
        return bad;
    }
}
