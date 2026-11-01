package ac.cult.cultac.checks.impl.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class CloudFlagCheckTest {
    /** The flag ids the cloud service sends, each the name of its check. */
    public static final List<String> NAMES = List.of(
            "CloudCombat",
            "CloudCombatExperimental",
            "CloudAutomation",
            "CloudAutomationExperimental",
            "CloudClient",
            "CloudClientExperimental",
            "CloudExploit",
            "CloudExploitExperimental");

    @Test
    void everyFlagIdNamesTheCheckOfThatName() {
        for (String name : NAMES) {
            Class<? extends CloudFlagCheck> type = CloudFlagCheck.of(name);
            assertNotNull(type, name);
            assertEquals(name, type.getSimpleName());
        }
        assertNull(CloudFlagCheck.of("Cloud"));
        assertNull(CloudFlagCheck.of("cloudcombat"));
    }

    @Test
    void aFlagIsOneViolationWithoutASetback() {
        OfflineCultTestBootstrap.installConfig();
        EmbeddedChannel channel = new EmbeddedChannel();
        User user = TestUsers.create(new User.Profile(UUID.randomUUID(), "Flagged"), channel);
        CultPlayer player = new CultPlayer(user);
        try {
            for (String name : NAMES) {
                CloudFlagCheck check = player.checkManager.getCheck(CloudFlagCheck.of(name));
                assertNotNull(check, name);
                assertEquals(name, check.getCheckName());

                assertTrue(check.raise(), name);

                assertEquals(1, check.violations, 0, name);
                assertFalse(player.getSetbackTeleportUtil().isPendingSetback(), name);
            }
        } finally {
            player.onRemove();
            channel.finishAndReleaseAll();
        }
    }
}
