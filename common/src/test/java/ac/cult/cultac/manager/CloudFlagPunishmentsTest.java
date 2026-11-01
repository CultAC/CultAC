package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.cloud.CloudFlagCheckTest;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The punishment groups that select the checks cloud flags raise. */
class CloudFlagPunishmentsTest {
    @Test
    void theDefaultCloudGroupSelectsEveryCloudFlag() throws Exception {
        Map<String, Object> groups;
        try (InputStream source = CloudFlagPunishmentsTest.class.getResourceAsStream("/punishments/en.yml")) {
            Map<String, Object> data = new Yaml().load(source);
            groups = (Map<String, Object>) data.get("Punishments");
        }
        int cloud = new ArrayList<>(groups.keySet()).indexOf("Cloud");

        withPlayer(new ArrayList<>(groups.values()), built -> {
            Set<String> names = names(built.get(cloud));
            assertTrue(names.containsAll(CloudFlagCheckTest.NAMES), names.toString());
            assertTrue(names.contains("Cloud"), names.toString());
        });
    }

    @Test
    void notExperimentalSelectsOnlyTheStableFlags() throws Exception {
        LinkedHashMap<String, Object> group = new LinkedHashMap<>(Map.of(
                "remove-violations-after", 86400,
                "checks", List.of("CloudCombat", "CloudAutomation", "CloudClient", "CloudExploit", "!Experimental"),
                "commands", List.of("1:0 [alert]", "1:0 [webhook]", "1:0 ban %player% Unfair advantage")));

        withPlayer(
                List.of(group),
                built -> assertEquals(
                        new TreeSet<>(List.of("CloudAutomation", "CloudClient", "CloudCombat", "CloudExploit")),
                        names(built.get(0))));
    }

    private static Set<String> names(PunishGroup group) {
        Set<String> names = new TreeSet<>();
        for (AbstractCheck check : group.checks) names.add(check.getCheckName());
        return names;
    }

    private static void withPlayer(List<Object> groups, Consumer<List<PunishGroup>> verify) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Punished"), channel);
        CultPlayer player = new CultPlayer(user);
        try {
            ConfigManager config = mock(ConfigManager.class);
            when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
            when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
            when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) groups);

            player.punishmentManager.reload(config);

            Field built = PunishmentManager.class.getDeclaredField("groups");
            built.setAccessible(true);
            List<PunishGroup> result = (List<PunishGroup>) built.get(player.punishmentManager);
            assertEquals(groups.size(), result.size());
            verify.accept(result);
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
