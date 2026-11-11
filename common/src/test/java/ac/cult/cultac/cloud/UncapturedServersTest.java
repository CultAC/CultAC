package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class UncapturedServersTest {
    @Test
    void namesMatchWholeAndIgnoringCase() {
        UncapturedServers servers = UncapturedServers.of(List.of("Hub", " waiting "));
        assertTrue(servers.matches("hub"));
        assertTrue(servers.matches("HUB"));
        assertTrue(servers.matches("Waiting"));
        assertTrue(servers.matches(" hub "));
        assertFalse(servers.matches("hub2"));
        assertFalse(servers.matches("ahub"));
        assertFalse(servers.matches("hu"));
        assertFalse(servers.matches("game"));
    }

    @Test
    void aStarMatchesAnyRunOfCharacters() {
        UncapturedServers servers = UncapturedServers.of(List.of("hub-*", "*-wait", "a*b*c"));
        assertTrue(servers.matches("hub-"));
        assertTrue(servers.matches("hub-1"));
        assertTrue(servers.matches("HUB-EU-2"));
        assertFalse(servers.matches("hub"));
        assertTrue(servers.matches("duel-wait"));
        assertTrue(servers.matches("-wait"));
        assertFalse(servers.matches("duel-waiting"));
        assertTrue(servers.matches("abc"));
        assertTrue(servers.matches("axxbyyc"));
        assertTrue(servers.matches("abcbc"));
        assertFalse(servers.matches("acb"));
        assertFalse(servers.matches("abcd"));
    }

    @Test
    void aLoneStarMatchesEveryNamedBackend() {
        UncapturedServers servers = UncapturedServers.of(List.of("*"));
        assertTrue(servers.matches("anything"));
        assertTrue(servers.matches("x"));
        assertFalse(servers.matches(""));
        assertFalse(servers.matches(null));
    }

    @Test
    void globAgreesWithARegexOverEveryShortPatternAndName() {
        String alphabet = "ab*";
        for (String pattern : words(alphabet, 4)) {
            String regex = pattern.replace("*", ".*");
            for (String name : words("ab", 5)) {
                assertEquals(name.matches(regex), UncapturedServers.glob(pattern, name), pattern + " ~ " + name);
            }
        }
    }

    @Test
    void emptyAndBlankListsMatchNothing() {
        assertSame(UncapturedServers.NONE, UncapturedServers.of(null));
        assertSame(UncapturedServers.NONE, UncapturedServers.of(List.of()));
        assertSame(UncapturedServers.NONE, UncapturedServers.of(Arrays.asList("", " ", null)));
        assertTrue(UncapturedServers.NONE.isEmpty());
        assertFalse(UncapturedServers.NONE.matches("hub"));
        assertFalse(UncapturedServers.of(List.of("hub")).matches(" "));
    }

    /** Every word of at most {@code length} letters of {@code alphabet}, the empty one included. */
    private static List<String> words(String alphabet, int length) {
        List<String> words = new ArrayList<>(List.of(""));
        for (int at = 0; at < words.size(); at++) {
            String word = words.get(at);
            if (word.length() == length) continue;
            for (char c : alphabet.toCharArray()) words.add(word + c);
        }
        return words;
    }
}
