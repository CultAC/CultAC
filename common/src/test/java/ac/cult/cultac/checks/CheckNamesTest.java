package ac.cult.cultac.checks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.visibility.AttackEntityVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractEntityVisibility;
import ac.cult.cultac.checks.impl.visibility.InteractPlayerVisibility;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckNamesTest {
    @Test
    void aCheckKeepsAFormerNameUpToTheVersionItWasRenamedAfter() {
        CheckNames names = CheckNames.of("AttackEntityVisibility").formerly(1, "AttackMobVisibility");
        assertEquals("AttackMobVisibility", names.in(1));
        assertEquals("AttackEntityVisibility", names.in(2));
        assertEquals("AttackEntityVisibility", names.latest());
    }

    @Test
    void aFormerNameCannotReachTheNewestVersionOrShareOne() {
        CheckNames names = CheckNames.of("New");
        assertThrows(IllegalArgumentException.class, () -> names.formerly(CheckNames.LATEST, "Old"));
        assertThrows(IllegalArgumentException.class, () -> names.formerly(0, "Old"));
        assertThrows(IllegalArgumentException.class, () -> names.formerly(1, "Old").formerly(1, "Older"));
    }

    @Test
    void aFileWithoutAStampIsWrittenInTheFirstVersion() {
        assertEquals(CheckNames.FIRST, CheckNames.version(StampedConfig.of(Map.of()), CheckNames.CONFIG_KEY));
        assertEquals(CheckNames.FIRST, CheckNames.version(StampedConfig.of(Map.of()), CheckNames.PUNISHMENTS_KEY));
        assertDoesNotThrow(() -> CheckNames.validate(StampedConfig.of(Map.of())));
    }

    @Test
    void eachFileIsReadInItsOwnVersion() {
        var config = StampedConfig.of(Map.of(CheckNames.PUNISHMENTS_KEY, 2));
        assertEquals(1, CheckNames.version(config, CheckNames.CONFIG_KEY));
        assertEquals(2, CheckNames.version(config, CheckNames.PUNISHMENTS_KEY));
        assertDoesNotThrow(() -> CheckNames.validate(config));
    }

    @Test
    void aStampThisBuildCannotReadIsRefused() {
        for (Object stamp : new Object[] {CheckNames.LATEST + 1, 0, -1, "two", 1.5, true}) {
            for (String key : new String[] {CheckNames.CONFIG_KEY, CheckNames.PUNISHMENTS_KEY}) {
                var config = StampedConfig.of(Map.of(key, stamp));
                var refusal = assertThrows(IllegalStateException.class, () -> CheckNames.validate(config));
                assertTrue(refusal.getMessage().contains(key + " is " + stamp), refusal.getMessage());
            }
        }
        assertDoesNotThrow(() -> CheckNames.validate(StampedConfig.of(Map.of(CheckNames.CONFIG_KEY, "2"))));
    }

    @Test
    void aCheckReportsTheNameOfTheVersionConfigYmlIsWrittenIn() {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Names"), channel);
        CultPlayer player = new CultPlayer(user);
        try {
            Check attack = player.checkManager.getCheck(AttackEntityVisibility.class);
            Check players = player.checkManager.getCheck(InteractPlayerVisibility.class);
            Check entities = player.checkManager.getCheck(InteractEntityVisibility.class);

            // The punishments.yml stamp does not move the names a check reports.
            for (ConfigManager config : new ConfigManager[] {
                StampedConfig.of(Map.of()), StampedConfig.of(Map.of(CheckNames.PUNISHMENTS_KEY, 2))
            }) {
                attack.reload(config);
                players.reload(config);
                entities.reload(config);
                assertEquals("AttackMobVisibility", attack.getCheckName());
                assertEquals("AttackMobVisibility", attack.getConfigName());
                assertEquals("AttackMobVisibility", attack.getDisplayName());
                assertEquals("InteractEntityVisibility", players.getCheckName());
                assertEquals("InteractEntityVisibility", players.getConfigName());
                assertEquals("InteractEntityVisibility", entities.getCheckName());
            }

            var renamed = StampedConfig.of(Map.of(CheckNames.CONFIG_KEY, 2));
            attack.reload(renamed);
            players.reload(renamed);
            entities.reload(renamed);
            assertEquals("AttackEntityVisibility", attack.getCheckName());
            assertEquals("AttackEntityVisibility", attack.getConfigName());
            assertEquals("AttackEntityVisibility", attack.getDisplayName());
            assertEquals("InteractPlayerVisibility", players.getCheckName());
            assertEquals("InteractPlayerVisibility", players.getConfigName());
            assertEquals("InteractEntityVisibility", entities.getCheckName());

            // What history and the cloud key a check by never moves.
            assertEquals("cult.attack.mob.visibility", attack.getStableKey());
            assertEquals("AttackEntityVisibility", attack.getLatestName());
            assertEquals("InteractPlayerVisibility", players.getLatestName());
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void permissionNodesCarryTheNamesOfThatVersionSoTheSplitChecksAreExemptedApart() {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Nodes"), channel);
        CultPlayer player = new CultPlayer(user);
        try {
            PlatformPlayer platform = mock(PlatformPlayer.class);
            when(platform.hasPermission("cult.exempt.interactentityvisibility")).thenReturn(true);
            when(platform.hasPermission("cult.exempt.attackmobvisibility")).thenReturn(true);
            player.platformPlayer = platform;
            Check attack = player.checkManager.getCheck(AttackEntityVisibility.class);
            Check players = player.checkManager.getCheck(InteractPlayerVisibility.class);
            Check entities = player.checkManager.getCheck(InteractEntityVisibility.class);
            List<Check> checks = List.of(attack, players, entities);

            // One check up to name version 1: its node exempts both halves.
            checks.forEach(check -> check.reload(StampedConfig.of(Map.of())));
            checks.forEach(Check::updatePermissions);
            assertTrue(attack.isExemptPermission());
            assertTrue(players.isExemptPermission());
            assertTrue(entities.isExemptPermission());

            // The reload that moves config.yml to version 2 reads the nodes of version 2 at once.
            checks.forEach(check -> check.reload(StampedConfig.of(Map.of(CheckNames.CONFIG_KEY, 2))));
            assertFalse(attack.isExemptPermission());
            assertFalse(players.isExemptPermission());
            assertTrue(entities.isExemptPermission());

            when(platform.hasPermission("cult.exempt.interactplayervisibility")).thenReturn(true);
            when(platform.hasPermission("cult.exempt.interactentityvisibility")).thenReturn(false);
            when(platform.hasPermission("cult.exempt.attackentityvisibility")).thenReturn(true);
            checks.forEach(Check::updatePermissions);
            assertTrue(attack.isExemptPermission());
            assertTrue(players.isExemptPermission());
            assertFalse(entities.isExemptPermission());
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aChecksOwnSettingsAreReadUnderTheNameOfThatVersion() {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Keys"), channel);
        CultPlayer player = new CultPlayer(user);
        try {
            Check attack = player.checkManager.getCheck(AttackEntityVisibility.class);
            Map<String, Object> settings =
                    Map.of("AttackMobVisibility.setbackvl", 3.0, "AttackEntityVisibility.setbackvl", 7.0);
            attack.reload(StampedConfig.of(settings));
            assertEquals(3.0, attack.getSetbackVL());

            Map<String, Object> stamped = new HashMap<>(settings);
            stamped.put(CheckNames.CONFIG_KEY, 2);
            attack.reload(StampedConfig.of(stamped));
            assertEquals(7.0, attack.getSetbackVL());
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
