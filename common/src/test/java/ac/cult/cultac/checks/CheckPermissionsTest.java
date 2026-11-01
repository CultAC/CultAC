package ac.cult.cultac.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.bedrock.player.BedrockPlayerState;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.permissions.PermissionDefaultValue;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckPermissionsTest {
    @Test
    void listsEveryCheckAJavaPlayerBuilds() {
        assertEquals(new TreeSet<>(CheckPermissions.IDS), checkIds(MovementPlatform.JAVA));
    }

    @Test
    void listsEveryCheckABedrockPlayerBuilds() {
        assertEquals(new TreeSet<>(CheckPermissions.IDS), checkIds(MovementPlatform.BEDROCK));
    }

    @Test
    void idsMatchTheListUnderATurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals(new TreeSet<>(CheckPermissions.IDS), checkIds(MovementPlatform.JAVA));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void registersEveryPermissionOfEveryCheckAsDeniedByDefault() {
        Map<String, PermissionDefaultValue> registered = new HashMap<>();
        CheckPermissions.register(registered::put);

        Set<String> expected = new HashSet<>();
        for (String id : CheckPermissions.IDS) {
            expected.add("cult.exempt." + id);
            expected.add("cult.nosetback." + id);
            expected.add("cult.nomodifypacket." + id);
        }
        assertEquals(expected, registered.keySet());
        assertEquals(Set.of(PermissionDefaultValue.FALSE), new HashSet<>(registered.values()));
    }

    private static Set<String> checkIds(MovementPlatform platform) {
        OfflineCultTestBootstrap.installConfig();
        UUID uuid = UUID.randomUUID();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(uuid, ".Check_Ids"), channel);
        CultPlayer player = platform == MovementPlatform.BEDROCK
                ? new CultPlayer(user, platform, new BedrockPlayerState(uuid))
                : new CultPlayer(user);
        try {
            Set<String> ids = new TreeSet<>();
            for (Check check : player.checkManager.allChecks.values()) {
                if (check.getConfigName() != null) ids.add(Check.permissionId(check.getConfigName()));
            }
            return ids;
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
