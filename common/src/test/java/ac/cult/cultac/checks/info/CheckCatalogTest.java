package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;

class CheckCatalogTest {
    @Test
    void listsEveryCheckAJavaPlayerBuilds() {
        assertEquals(catalog(), identities(MovementPlatform.JAVA));
    }

    @Test
    void listsEveryCheckABedrockPlayerBuilds() {
        assertEquals(catalog(), identities(MovementPlatform.BEDROCK));
    }

    @Test
    void noCheckIsListedTwice() {
        Set<String> names = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) assertTrue(names.add(entry.name()), entry.name());
    }

    @Test
    void everyCheckHasAnAlertDescription() {
        List<String> undescribed = new ArrayList<>();
        for (MovementPlatform platform : MovementPlatform.values()) {
            for (Check check : checks(platform)) {
                String description = check.getDescription();
                if (description == null || description.isBlank() || description.equals(CheckData.DEFAULT_DESCRIPTION))
                    undescribed.add(check.getCheckName());
            }
        }
        assertEquals(List.of(), undescribed);
    }

    @Test
    void aCheckIsFoundByItsNameConfigNameOrAltName() {
        assertEquals("SprintD", CheckCatalog.find("sprintd").name());
        assertEquals("AntiKB", CheckCatalog.find("KNOCKBACK").name());
        assertEquals("AntiKB", CheckCatalog.find("antiknockback").name());
        assertEquals("Timer", CheckCatalog.find("timera").name());
        assertNull(CheckCatalog.find("Sprint"));
    }

    @Test
    void aPartOfANameListsEveryCheckContainingIt() {
        assertEquals(
                List.of("NegativeTimer", "TickTimer", "Timer", "TimerVehicle"),
                CheckCatalog.containing("TIMER").stream()
                        .map(CheckCatalog.Entry::name)
                        .toList());
    }

    private static Set<Identity> catalog() {
        Set<Identity> identities = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            identities.add(
                    new Identity(entry.name(), entry.configName(), entry.runtimeAltName(), entry.experimental()));
        }
        return identities;
    }

    private static Set<Identity> identities(MovementPlatform platform) {
        Set<Identity> identities = new HashSet<>();
        for (Check check : checks(platform)) {
            identities.add(new Identity(
                    check.getCheckName(), check.getConfigName(), check.getAlternativeName(), check.isExperimental()));
        }
        return identities;
    }

    private static List<Check> checks(MovementPlatform platform) {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Info"), channel);
        CultPlayer player = platform == MovementPlatform.BEDROCK
                ? new CultPlayer(user, platform, new BedrockPlayerState(uuid))
                : new CultPlayer(user);
        try {
            List<Check> checks = new ArrayList<>();
            for (Check check : player.checkManager.allChecks.values()) {
                if (check.getConfigName() != null) checks.add(check);
            }
            return checks;
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    record Identity(String name, String configName, String altName, boolean experimental) {}
}
