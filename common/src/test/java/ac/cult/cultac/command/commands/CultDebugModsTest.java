package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMod;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMods;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.GrimUser;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CultDebugModsTest {
    private List<String> output(ClientMods mods) throws Exception {
        return output(mods, false);
    }

    private List<String> output(ClientMods mods, boolean internal) throws Exception {
        return components(mods, internal, null).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    private List<Component> components(ClientMods mods, boolean internal, @Nullable String brand) throws Exception {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var variables = CultAPI.INSTANCE.getExternalAPI().getVariableReplacements();
        Function<GrimUser, String> registered = variables.get("%brand%");
        CultAPI.INSTANCE.getExternalAPI().registerVariable("%brand%", GrimUser::getBrand);
        try (var placeholders = new PlainPlaceholders()) {
            CultPlayer player = new CultPlayer(TestUsers.create(new User.Profile(UUID.randomUUID(), "Alice"), channel));
            player.pluginChannelManager.handleChannelRegistered("fabric:test");
            if (brand != null) player.checkManager.getCheck(ClientBrand.class).restore(brand);
            Sender sender = mock(Sender.class);
            var method =
                    CultDebug.class.getDeclaredMethod(
                            "sendMods", Sender.class, CultPlayer.class, ClientMods.class, boolean.class);
            method.setAccessible(true);
            method.invoke(null, sender, player, mods, internal);
            var messages = ArgumentCaptor.forClass(Component.class);
            verify(sender, atLeastOnce()).sendMessage(messages.capture());
            return messages.getAllValues();
        } finally {
            CultAPI.INSTANCE.getExternalAPI().registerVariable("%brand%", registered);
            channel.finishAndReleaseAll();
        }
    }

    private static Component brandLine(List<Component> lines) {
        return lines.stream()
                .filter(line ->
                        PlainTextComponentSerializer.plainText().serialize(line).contains("Client brand: "))
                .findFirst()
                .orElseThrow();
    }

    /** Every part of {@code component} with a click, hover or insertion, or a colour other than white. */
    private static List<Component> styled(Component component) {
        return styled(component, null);
    }

    private static List<Component> styled(Component component, @Nullable TextColor inherited) {
        TextColor color = component.color() != null ? component.color() : inherited;
        List<Component> found = new ArrayList<>();
        if (component.clickEvent() != null || component.hoverEvent() != null || component.insertion() != null)
            found.add(component);
        if (component instanceof TextComponent text
                && text.content().contains("vanilla")
                && !NamedTextColor.WHITE.equals(color)) found.add(component);
        for (Component child : component.children()) found.addAll(styled(child, color));
        return found;
    }

    @Test
    void aBrandWithFormattingIsShownAsWrittenInWhite() throws Exception {
        String brand =
                "<click:run_command:'/cult debug channels Alice'><hover:show_text:'x'><insert:y>&cvanilla %player%";
        Component line = brandLine(components(null, false, brand));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(line).endsWith("Client brand: " + brand));
        assertEquals(List.of(), styled(line));
    }

    @Test
    void anOrdinaryBrandIsShownAsBefore() throws Exception {
        String brand = "Lunar Client (1.21.11-2523/master) vanilla/fabric ünï";
        Component line = brandLine(components(null, false, brand));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(line).endsWith("Client brand: " + brand));
        assertEquals(List.of(), styled(line));
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
