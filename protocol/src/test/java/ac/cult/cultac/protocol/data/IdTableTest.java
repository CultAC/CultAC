package ac.cult.cultac.protocol.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ac.cult.cultac.protocol.ProtocolResolutionException;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IdTableTest {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]*:[a-z0-9/._-]*");

    private static void assertSameAsRegex(String name) {
        assertEquals(NAME.matcher(name).matches(), IdTable.validName(name), () -> "name " + escape(name));
    }

    @Test
    void everyCharAtEveryPositionAgreesWithTheIdentifierPattern() {
        for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
            String ch = String.valueOf((char) c);
            assertSameAsRegex(ch);
            assertSameAsRegex(ch + ":");
            assertSameAsRegex(":" + ch);
            assertSameAsRegex("ns" + ch + ":path");
            assertSameAsRegex("ns:pa" + ch + "th");
            assertSameAsRegex("ns:path" + ch);
            assertSameAsRegex(ch + "ns:path");
        }
    }

    @Test
    void structuralCasesAgreeWithTheIdentifierPattern() {
        for (String name : List.of(
                "",
                ":",
                "minecraft:",
                ":stone",
                "minecraft:stone",
                "minecraft::stone",
                "minecraft:stone:slab",
                "mine/craft:stone",
                "minecraft:block/stone",
                "minecraft",
                "Minecraft:stone",
                "minecraft:Stone",
                "minecraft:st one",
                "a.b-c_d:e/f.g-h_i",
                "minecraft:" + new String(Character.toChars(0x1F600)),
                new String(Character.toChars(0x1F600)) + ":stone")) {
            assertSameAsRegex(name);
        }
    }

    @Test
    void randomNamesAgreeWithTheIdentifierPattern() {
        String alphabet = "az09_.-/:AZ " + (char) 0xE9 + (char) 0xD83D;
        Random random = new Random(0x1D7AB1EL);
        for (int i = 0; i < 200_000; i++) {
            var name = new StringBuilder();
            int length = random.nextInt(8);
            for (int j = 0; j < length; j++) name.append(alphabet.charAt(random.nextInt(alphabet.length())));
            assertSameAsRegex(name.toString());
        }
    }

    @Test
    void anInvalidNameKeepsItsMessage() {
        var failure = assertThrows(
                ProtocolResolutionException.class, () -> new IdTable("synced", List.of("minecraft:a", "Bad:name")));
        assertEquals("Invalid/duplicate name in synced: Bad:name", failure.getMessage());
    }

    private static String escape(String name) {
        var out = new StringBuilder();
        for (char c : name.toCharArray()) out.append(String.format("\\u%04x", (int) c));
        return out.toString();
    }
}
