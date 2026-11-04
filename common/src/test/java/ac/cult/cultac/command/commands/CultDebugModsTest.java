package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMod;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMods;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CultDebugModsTest {
    private List<String> output(ClientMods mods) throws Exception {
        return output(mods, false);
    }

    private List<String> output(ClientMods mods, boolean internal) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        try (var placeholders = new PlainPlaceholders()) {
            CultPlayer player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Alice"), channel));
            player.pluginChannelManager.handleChannelRegistered("fabric:test");
            Sender sender = mock(Sender.class);
            var method =
                    CultDebug.class.getDeclaredMethod(
                            "sendMods", Sender.class, CultPlayer.class, ClientMods.class, boolean.class);
            method.setAccessible(true);
            method.invoke(null, sender, player, mods, internal);
            var messages = ArgumentCaptor.forClass(Component.class);
            verify(sender, atLeastOnce()).sendMessage(messages.capture());
            return messages.getAllValues().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .toList();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingApolloExplainsScopeAndShowsObservedClientData() throws Exception {
        var lines = output(null);
        assertTrue(lines.stream().anyMatch(s -> s.contains("Apollo reports mods only for Lunar Client")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Client brand:")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("fabric:test")));
        assertFalse(lines.stream().anyMatch(s -> s.contains("server needs")));
    }

    @Test
    void disconnectedApolloDoesNotClaimToIdentifyTheClient() throws Exception {
        var lines = output(new ClientMods(1, false, List.of()));
        assertTrue(lines.stream().anyMatch(s -> s.contains("not connected to Apollo")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("fabric:test")));
        assertFalse(lines.stream().anyMatch(s -> s.contains("not a Lunar Client player")));
    }

    @Test
    void lunarModsRemainGroupedAndClientTextIsLiteral() throws Exception {
        var lines = output(
                new ClientMods(1, true, List.of(new ClientMod("example", "<red>Example", "1.0", "FABRIC_EXTERNAL"))));
        assertTrue(lines.stream().anyMatch(s -> s.contains("FABRIC_EXTERNAL")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("<red>Example")));
        assertFalse(lines.stream().anyMatch(s -> s.contains("fabric:test")));
    }

    @Test
    void modsThatComeWithLunarAreHiddenUnlessAskedFor() throws Exception {
        var mods = new ClientMods(
                1,
                true,
                List.of(
                        new ClientMod("sodium", "Sodium", "0.6.0", "FABRIC_INTERNAL"),
                        new ClientMod("example", "Example", "1.0", "FABRIC_EXTERNAL")));

        var lines = output(mods);
        assertTrue(lines.stream().anyMatch(s -> s.contains("added 1 mods")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("1 mods that come with Lunar Client are hidden")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Example")));
        assertFalse(lines.stream().anyMatch(s -> s.contains("Sodium")));

        var all = output(mods, true);
        assertTrue(all.stream().anyMatch(s -> s.contains("has 2")));
        assertTrue(all.stream().anyMatch(s -> s.contains("Sodium")));
        assertTrue(all.stream().anyMatch(s -> s.contains("Example")));
        assertFalse(all.stream().anyMatch(s -> s.contains("hidden")));
    }
}
