package ac.cult.cultac.checks.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.CheckNames;
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
    void listsEveryCheckAJavaPlayerBuildsInEveryNameVersion() {
        for (int version = CheckNames.FIRST; version <= CheckNames.LATEST; version++) {
            assertEquals(catalog(version), identities(MovementPlatform.JAVA, version), "name version " + version);
        }
    }

    @Test
    void listsEveryCheckABedrockPlayerBuildsInEveryNameVersion() {
        for (int version = CheckNames.FIRST; version <= CheckNames.LATEST; version++) {
            assertEquals(catalog(version), identities(MovementPlatform.BEDROCK, version), "name version " + version);
        }
    }

    @Test
    void explainsEveryCheckAPlayerBuilds() {
        List<String> unexplained = new ArrayList<>();
        for (MovementPlatform platform : MovementPlatform.values()) {
            for (Check check : checks(platform)) {
                String name = check.getLatestName();
                CheckCatalog.Entry entry = CheckCatalog.find(name);
                if (entry == null || !entry.name().equals(name)) unexplained.add(name);
            }
        }
        assertEquals(List.of(), unexplained);
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
    void everyCheckDeclaresAStableKey() {
        List<String> unkeyed = new ArrayList<>();
        for (MovementPlatform platform : MovementPlatform.values()) {
            for (Check check : checks(platform)) {
                if (check.getStableKey().isEmpty()) unkeyed.add(check.getCheckName());
            }
        }
        assertEquals(List.of(), unkeyed);
    }

    @Test
    void aCheckIsFoundByItsNameConfigNameOrAltName() {
        assertEquals("SprintD", CheckCatalog.find("sprintd").name());
        assertEquals("AntiKB", CheckCatalog.find("KNOCKBACK").name());
        assertEquals("AntiKB", CheckCatalog.find("antiknockback").name());
        assertEquals("Timer", CheckCatalog.find("timera").name());
        assertNull(CheckCatalog.find("Sprint"));
        assertNull(CheckCatalog.find(CheckData.NO_ALTERNATIVE_NAME));
    }

    @Test
    void aCheckIsFoundByANameOfAnOlderNameVersionUnlessACheckHasThatNameNow() {
        assertEquals("AttackEntityVisibility", CheckCatalog.find("attackmobvisibility").name());
        assertEquals("bAttackEntityVisibility", CheckCatalog.find("bAttackMobVisibility").name());
        // InteractPlayerVisibility had this name too, up to name version 1.
        assertEquals("InteractEntityVisibility", CheckCatalog.find("InteractEntityVisibility").name());
        assertEquals("InteractPlayerVisibility", CheckCatalog.find("interactplayervisibility").name());
    }

    @Test
    void aPartOfANameListsTheChecksThatNameVersionNamesWithIt() {
        assertEquals(
                List.of("AttackEntityVisibility", "bAttackEntityVisibility"),
                CheckCatalog.containing("mobvis", 1).stream()
                        .map(CheckCatalog.Entry::name)
                        .toList());
        assertEquals(List.of(), CheckCatalog.containing("mobvis", 2));
        assertEquals(
                List.of("InteractEntityVisibility", "InteractPlayerVisibility"),
                CheckCatalog.containing("InteractEntity", 1).stream()
                        .map(CheckCatalog.Entry::name)
                        .toList());
    }

    @Test
    void aPartOfANameListsEveryCheckContainingIt() {
        assertEquals(
                List.of("TickTimer", "Timer", "TimerVehicle"),
                CheckCatalog.containing("TIMER").stream()
                        .map(CheckCatalog.Entry::name)
                        .toList());
    }

    private static Set<Identity> catalog(int version) {
        Set<Identity> identities = new HashSet<>();
        for (CheckCatalog.Entry entry : CheckCatalog.ENTRIES) {
            identities.add(new Identity(
                    entry.name(),
                    entry.name(version),
                    entry.configName(version),
                    entry.altName(),
                    entry.experimental()));
        }
        return identities;
    }

    private static Set<Identity> identities(MovementPlatform platform, int version) {
        Set<Identity> identities = new HashSet<>();
        for (Check check : checks(platform)) {
            identities.add(new Identity(
                    check.getLatestName(),
                    check.nameIn(version),
                    check.configNameIn(version),
                    check.getAlternativeName(),
                    check.isExperimental()));
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

    record Identity(String latestName, String name, String configName, String altName, boolean experimental) {}
}
