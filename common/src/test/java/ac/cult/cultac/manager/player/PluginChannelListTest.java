package ac.cult.cultac.manager.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.player.ClientClassification;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
    void repeatedChannelPayloadsUseBoundedWork() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var manager = new PluginChannelManager(null);
        var channels = new CountingChannels();
        var field = PluginChannelManager.class.getDeclaredField("registeredChannels");
        field.setAccessible(true);
        field.set(manager, channels);
        for (int i = 0; i < PluginChannelManager.MAX_REGISTERED_CHANNELS; i++) {
            manager.handleChannelRegistered("test:" + i);
        }
        channels.visits = 0;
        manager.handle("minecraft:unregister", "x\0".repeat(4096).getBytes(StandardCharsets.UTF_8), null);
        manager.handleChannelUnregistered("test:0");
        manager.handle(
                "minecraft:register", "fabric:registry/sync\0".repeat(380).getBytes(StandardCharsets.UTF_8), null);
        assertEquals(ClientClassification.Type.FABRIC, manager.getChannelType());
        assertEquals(
                PluginChannelManager.MAX_REGISTERED_CHANNELS,
                manager.getRegisteredChannels().size());
        manager.handleChannelRegistered("forge:test");
        assertEquals(
                PluginChannelManager.MAX_REGISTERED_CHANNELS,
                manager.getRegisteredChannels().size());
        manager.handle(
                "minecraft:unregister", "fabric:registry/sync\0".repeat(380).getBytes(StandardCharsets.UTF_8), null);
        assertEquals(ClientClassification.Type.UNSET, manager.getChannelType());
        assertEquals(0, channels.visits);
    }

    @Test
    void channelCountsRetainEachActiveType() {
        var manager = new PluginChannelManager(null);
        manager.handleChannelRegistered("forge:first");
        manager.handleChannelRegistered("forge:first");
        manager.handleChannelRegistered("forge:second");
        manager.handleChannelRegistered("fabric:registry/sync");
        assertEquals(ClientClassification.Type.UNKNOWN, manager.getChannelType());
        manager.handleChannelUnregistered("forge:first");
        assertEquals(ClientClassification.Type.UNKNOWN, manager.getChannelType());
        manager.handleChannelUnregistered("forge:second");
        assertEquals(ClientClassification.Type.FABRIC, manager.getChannelType());
        manager.handleChannelUnregistered("fabric:registry/sync");
        assertEquals(ClientClassification.Type.UNSET, manager.getChannelType());
    }

    private static final class CountingChannels extends LinkedHashSet<String> {
        int visits;

        @Override
        public Iterator<String> iterator() {
            Iterator<String> delegate = super.iterator();
            return new Iterator<>() {
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                public String next() {
                    visits++;
                    return delegate.next();
                }
            };
        }
    }

    @Test
    void emptyPayloadDeclaresNothing() {
        assertEquals(List.of(), PluginChannelManager.channelList(new byte[0]));
    }
}
