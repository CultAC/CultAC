package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.cloud.CaptureFixture.FakeUplink;
import ac.cult.cultac.cloud.intake.v3.IntakeMessage;
import ac.cult.cultac.cloud.intake.v3.Join;
import ac.cult.cultac.cloud.intake.v3.Record;
import ac.cult.cultac.platform.api.ServerFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CloudCaptureFactsTest {
    @AfterEach
    void restore() {
        CloudCapture.segmentBinder(SegmentBinder.NONE);
    }

    @Test
    void theServerThenThePlayerThenSendCheckFlags() {
        Map<String, String> server = new LinkedHashMap<>();
        server.put("max-no-damage-ticks", "20");
        server.put("attack-speed-base", "4.0");
        server.put("send-check-flags", "false");
        Map<String, String> player = new LinkedHashMap<>();
        player.put("attack-speed-base", "1024.0");
        player.put("combat-mode", "legacy");

        ServerFacts merged =
                CloudCapture.merged(new ServerFacts(50_000, "Paper 26.3", server), ServerFacts.settings(player));

        assertEquals(50_000, merged.tickMicros());
        assertEquals("Paper 26.3", merged.server());
        assertEquals("20", merged.settings().get("max-no-damage-ticks"));
        assertEquals("1024.0", merged.settings().get("attack-speed-base"));
        assertEquals("legacy", merged.settings().get("combat-mode"));
        assertEquals("true", merged.settings().get("send-check-flags"));
    }

    @Test
    void noFactsStillSayCheckFlagsGoOut() {
        ServerFacts merged = CloudCapture.merged(null, null);
        assertEquals(0, merged.tickMicros());
        assertNull(merged.server());
        assertEquals(Map.of("send-check-flags", "true"), merged.settings());
    }

    @Test
    void thePlayersFactsReachTheJoinAndLaterOnesAMeta() {
        try (CaptureFixture fixture = new CaptureFixture(CaptureFixture.Host.PROXY, false)) {
            var platform = fixture.user.getPlayer();
            Mockito.when(platform.cloudFacts()).thenReturn(ServerFacts.settings(Map.of("combat-mode", "legacy")));
            CloudCapture.segmentBinder((player, bind, playing) -> bind.accept("a"));
            fixture.install();
            FakeUplink a = fixture.uplinks.socket("a");
            Join join = a.of(IntakeMessage.KindCase.JOIN).get(0).getJoin();
            assertEquals("legacy", join.getSettingsMap().get("combat-mode"));
            assertEquals("true", join.getSettingsMap().get("send-check-flags"));
            a.captureAll(fixture.player.getUniqueId());

            Mockito.when(platform.cloudFacts()).thenReturn(ServerFacts.settings(Map.of("combat-mode", "modern")));
            CloudCapture.factsChanged(fixture.player);
            fixture.tickEnd();
            List<Map<String, String>> metas = new ArrayList<>();
            for (IntakeMessage message : a.of(IntakeMessage.KindCase.FRAME)) {
                for (Record record : message.getFrame().getRecordsList()) {
                    if (record.getBodyCase() == Record.BodyCase.META)
                        metas.add(record.getMeta().getSettingsMap());
                }
            }
            assertEquals(1, metas.size());
            assertEquals("modern", metas.get(0).get("combat-mode"));
            assertEquals("true", metas.get(0).get("send-check-flags"));
        }
    }
}
