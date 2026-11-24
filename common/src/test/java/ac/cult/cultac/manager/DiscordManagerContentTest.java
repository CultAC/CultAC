package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscordManagerContentTest {
    @Test
    void clientTypeFollowsTheFirstBrandLineUnlessALineShowsIt() {
        assertEquals(
                List.of("**Player**: `%player%`", "**Brand**: `%brand%`", DiscordManager.CLIENT_TYPE_LINE, "%brand%"),
                DiscordManager.withClientType(List.of("**Player**: `%player%`", "**Brand**: `%brand%`", "%brand%")));
        List<String> typed = List.of("**Brand**: `%brand%`", "**Channels**: `%channeltype%`");
        assertEquals(typed, DiscordManager.withClientType(typed));
        List<String> brandless = List.of("**Player**: `%player%`");
        assertEquals(brandless, DiscordManager.withClientType(brandless));
    }
}
