package ac.cult.blocksim.generator;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourceScannerTest {
    @TempDir Path root;

    @Test void nestedTypesOverloadsAndTagReadsAreSeparateAuditEntries() throws Exception {
        Path file = root.resolve("world/level/block/Fixture.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package net.minecraft.world.level.block;
            class Fixture {
                int updateShape(int state) { return BlockTags.SNOW.ordinal(); }
                int updateShape(int state, String other) { return 2; }
                class Nested { int useOn() { return 3; } }
                int unrelated() { return 4; }
            }
            """);
        var entries = SourceScanner.scan(root);
        assertEquals(3, entries.size());
        assertEquals("BlockTags.SNOW", entries.get(0).tags());
        assertNotEquals(entries.get(0).signature(), entries.get(1).signature());
        assertEquals("net.minecraft.world.level.block.Fixture$Nested", entries.get(2).owner());
        assertTrue(entries.stream().allMatch(e -> e.tsv().contains("\tunmapped\t")));
    }

    @Test void bodyHashIgnoresCommentsButChangesWithClientLogic() throws Exception {
        Path file = root.resolve("world/item/Fixture.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package net.minecraft.world.item; class Fixture { int use() { return 1; } }");
        String original = SourceScanner.scan(root).getFirst().bodyHash();
        Files.writeString(file, "package net.minecraft.world.item; class Fixture { int use() { /* note */ return 1; } }");
        assertEquals(original, SourceScanner.scan(root).getFirst().bodyHash());
        Files.writeString(file, "package net.minecraft.world.item; class Fixture { int use() { return 2; } }");
        assertNotEquals(original, SourceScanner.scan(root).getFirst().bodyHash());
    }

    @Test void malformedSourceCannotSilentlyReduceCoverage() throws Exception {
        Path file = root.resolve("world/item/Fixture.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Fixture { int use() { ");
        assertThrows(java.io.IOException.class, () -> SourceScanner.scan(root));
    }

    @Test void generationRefusesUnpinnedGameBytes() throws Exception {
        Path jar = root.resolve("wrong.jar");
        Files.writeString(jar, "not the pinned game");
        assertThrows(IllegalArgumentException.class, () -> GenerationLauncher.main(new String[]{root.toString(), jar.toString(), "unused", "unused", "unused"}));
    }
}
