package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CheckCatalogTest {
    record Identity(String name, String configName, String altName, boolean experimental) {}

    @Test
    void listsEveryCheckAJavaPlayerBuilds() {
        assertEquals(catalog(), built(MovementPlatform.JAVA, CheckCatalogTest::identity));
    }

    @Test
    void listsEveryCheckABedrockPlayerBuilds() {
        assertEquals(catalog(), built(MovementPlatform.BEDROCK, CheckCatalogTest::identity));
    }

    @Test
    void listsEachCheckOnce() {
        Set<String> names = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) assertTrue(names.add(entry.name()), entry.name());
    }

    @Test
    void givesEveryCheckAnAlertDescription() {
        List<String> undescribed = new ArrayList<>();
        for (MovementPlatform platform : MovementPlatform.values()) {
            built(platform, check -> {
                String description = check.getDescription();
                if (description == null || description.isBlank() || description.equals(CheckData.DEFAULT_DESCRIPTION))
                    undescribed.add(check.getCheckName());
                return check.getCheckName();
            });
        }
        assertEquals(List.of(), undescribed);
    }

    @Test
    void findsACheckByAnyOfItsNamesIgnoringCase() {
        assertEquals("SprintD", CheckCatalog.find("sprintd").name());
        assertEquals("AntiKB", CheckCatalog.find("KNOCKBACK").name());
        assertEquals("AntiKB", CheckCatalog.find("antiknockback").name());
        assertEquals("Timer", CheckCatalog.find("timera").name());
        assertNull(CheckCatalog.find("Sprint"));
        assertNotEquals(List.of(), CheckCatalog.containing("badpackets"));
    }

    private static Set<Identity> catalog() {
        Set<Identity> identities = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            identities.add(
                    new Identity(entry.name(), entry.configName(), entry.runtimeAltName(), entry.experimental()));
        }
        return identities;
    }

    private static Identity identity(Check check) {
        return new Identity(
                check.getCheckName(), check.getConfigName(), check.getAlternativeName(), check.isExperimental());
    }

    private static <T> Set<T> built(MovementPlatform platform, Function<Check, T> read) {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Info"), channel);
        CultPlayer player = platform == MovementPlatform.BEDROCK
                ? new CultPlayer(user, platform, new BedrockPlayerState(uuid))
                : new CultPlayer(user);
        try {
            Set<T> values = new HashSet<>();
            for (Check check : player.checkManager.allChecks.values()) {
                if (check.getConfigName() != null) values.add(read.apply(check));
            }
            return values;
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
