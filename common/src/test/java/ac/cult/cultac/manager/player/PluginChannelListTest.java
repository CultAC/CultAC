package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PluginChannelListTest {
    @Test
    void registerPayloadSplitsOnNul() {
        byte[] payload = "fabric:registry/sync\0lunar:apollo\0\0voicechat:state\0".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                List.of("fabric:registry/sync", "lunar:apollo", "voicechat:state"),
                PluginChannelManager.channelList(payload));
    }

    @Test
    void emptyPayloadDeclaresNothing() {
        assertEquals(List.of(), PluginChannelManager.channelList(new byte[0]));
    }
}
