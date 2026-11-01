package ac.cult.cultac.manager.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.cloud.CloudSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalSyncServiceTest {
    private static final List<String> LANGUAGES =
            List.of("de", "en", "es", "fr", "it", "ja", "nl", "pl", "pt", "ro", "ru", "tr", "zh");

    private static CloudSettings cloud(String apiKey, boolean team) {
        return new CloudSettings(
                "https://intake.example", apiKey, CloudSettings.OWN_SERVER_ID, "lobby", List.of(5), false, team);
    }

    @Test
    void joinChecksRunOnlyWhileTheUploadRunsAndOffersTeamSync() {
        assertTrue(PortalSyncService.configured(cloud("key", true)));
        assertFalse(PortalSyncService.configured(cloud("key", false)));
        assertFalse(PortalSyncService.configured(cloud("", true)));
        assertFalse(PortalSyncService.configured(CloudSettings.OFF));
        assertFalse(PortalSyncService.configured(null));
    }

    @Test
    void everyShippedConfigLeavesTeamSyncOffAndNamesNoRestCall() throws IOException {
        for (String language : LANGUAGES) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config/" + language + ".yml")) {
                assertNotNull(in, language);
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\n  team-sync: false"), language);
                assertTrue(text.contains("CULTAC_CLOUD_TEAM_SYNC"), language);
                assertFalse(text.contains("cloud at cloud.url"), language);
            }
        }
    }
}
