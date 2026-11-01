package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.command.IsolatedCommandManager;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.network.routing.TestNetworks;
import ac.cult.cultac.network.routing.TestNetworks.Players;
import ac.cult.cultac.platform.api.sender.Sender;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CultFindPlayerTest {
    private final CommandManager<Sender> manager = new IsolatedCommandManager();
    private final Sender sender = mock(Sender.class);
    private final List<Component> sent = new ArrayList<>();
    private final TestNetworks networks = new TestNetworks();
    private final Players here = new Players()
            .with(UUID.randomUUID(), "bob", null, "vanilla")
            .with(UUID.randomUUID(), "Adam", null, "lunarclient:1.8.9");
    private final Players onP7 = new Players()
            .with(UUID.randomUUID(), "Alice", "lobby", "vanilla")
            .with(UUID.randomUUID(), "Carl", "survival", "Fabric Loader")
            .with(UUID.randomUUID(), "Dana", "lobby", null);
    private PlainPlaceholders placeholders;

    @BeforeAll
    static void config() {
        OfflineCultTestBootstrap.installConfig();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        placeholders = new PlainPlaceholders();
        when(sender.hasPermission(anyString())).thenReturn(true);
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(sender).sendMessage(any(Component.class));
        new CultFindPlayer().register(manager, null);
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        NetworkModule.stop();
        placeholders.close();
    }

    /** This instance, p3, as the current routing, with p7 heard from. */
    private void networked() {
        TestNetworks.install(networks.service("p3").platform(here).start());
        networks.service("p7").platform(onP7).start();
    }

    private List<String> run(String line) {
        sent.clear();
        manager.commandExecutor()
                .executeCommand(sender, line)
                .handle((ran, failure) -> null)
                .join();
        return sent.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    private List<String> suggest(String input) {
        return manager.suggestionFactory().suggestImmediately(sender, input).list().stream()
                .map(Suggestion::suggestion)
                .toList();
    }

    private ClickEvent click(int line) {
        return sent.get(line).clickEvent();
    }

    @Test
    void theSummaryCountsEveryBrandOnTheNetworkMostPlayersFirst() {
        networked();
        assertEquals(
                List.of(
                        "Cult » Client brands in use · 5 online · click a brand to see who uses it",
                        "» vanilla (2)",
                        "» Fabric Loader (1)",
                        "» lunarclient:1.8.9 (1)",
                        "» unknown (1)"),
                run("cult findplayer"));
        assertEquals(ClickEvent.runCommand("/cult findplayer brand=vanilla"), click(1));
        assertEquals(ClickEvent.runCommand("/cult findplayer brand=Fabric Loader"), click(2));
        assertEquals(ClickEvent.runCommand("/cult findplayer brand=unknown"), click(4));
        assertEquals(run("cult findplayer"), run("cult findplayer brand="));
    }

    @Test
    void theFilterListsMatchingPlayersByNameIgnoringCase() {
        networked();
        assertEquals(
                List.of(
                        "Cult » Players whose brand contains VAN · 2 online",
                        "» Alice on lobby · vanilla",
                        "» bob on p3 · vanilla"),
                run("cult findplayer brand=VAN"));
        assertEquals(ClickEvent.runCommand("/cult profile Alice"), click(1));
        assertEquals(ClickEvent.runCommand("/cult profile bob"), click(2));
        assertEquals(
                List.of(
                        "Cult » Players whose brand contains fabric lo · 1 online",
                        "» Carl on survival · Fabric Loader"),
                run("cult findplayer BRAND=fabric lo"));
    }

    @Test
    void unknownBrandsAreListedUnderUnknown() {
        networked();
        assertEquals(
                List.of("Cult » Players whose brand contains unknown · 1 online", "» Dana on lobby · unknown"),
                run("cult findplayer brand=unknown"));
    }

    @Test
    void noMatchSaysSo() {
        networked();
        assertEquals(
                List.of("Cult » Nobody online uses a brand containing forge."), run("cult findplayer brand=forge"));
    }

    @Test
    void textOtherThanAFilterExplainsTheFilter() {
        networked();
        List<String> lines = run("cult findplayer lunar");
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("findplayer brand=lunar"), lines.getFirst());
    }

    @Test
    void atMostAHundredPlayersAreListed() {
        for (int i = 0; i < 105; i++) here.with(UUID.randomUUID(), String.format("P%03d", i), null, "forge");
        networked();
        List<String> lines = run("cult findplayer brand=forge");
        assertEquals(102, lines.size());
        assertEquals("Cult » Players whose brand contains forge · 105 online", lines.getFirst());
        assertEquals("» P000 on p3 · forge", lines.get(1));
        assertEquals("» P099 on p3 · forge", lines.get(100));
        assertEquals("...and 5 more. Type more of the brand name to narrow it down.", lines.getLast());
    }

    @Test
    void aBrandShowsAsPlainText() {
        here.with(UUID.randomUUID(), "Eve", null, "<red>&cmal<click:run_command:/op Eve>§4ware");
        networked();
        List<String> lines = run("cult findplayer brand=mal");
        assertEquals("» Eve on p3 · <red>&cmal<click:run_command:/op Eve>ware", lines.get(1));
        assertEquals(ClickEvent.runCommand("/cult profile Eve"), click(1));
    }

    @Test
    void withoutRoutingOnlyThisServersPlayersAreCounted() {
        TestNetworks.install(
                networks.service("p3").platform(here).transport(null).start());
        networks.service("p7").platform(onP7).start();
        assertEquals(
                List.of(
                        "Cult » Client brands in use · 2 online · click a brand to see who uses it",
                        "» lunarclient:1.8.9 (1)",
                        "» vanilla (1)"),
                run("cult findplayer"));
    }

    @Test
    void tabCompletionOffersTheFilterWithEachBrandOnline() {
        networked();
        assertEquals(
                List.of("brand=vanilla", "brand=Fabric Loader", "brand=lunarclient:1.8.9", "brand=unknown"),
                suggest("cult findplayer "));
        assertEquals(List.of("brand=lunarclient:1.8.9"), suggest("cult findplayer brand=LU"));
    }

    @Test
    void theCommandNeedsItsPermission() {
        networked();
        when(sender.hasPermission(anyString())).thenReturn(false);
        assertEquals(List.of(), run("cult findplayer"));
        when(sender.hasPermission(CultFindPlayer.PERMISSION)).thenReturn(true);
        assertEquals(5, run("cult findplayer").size());
    }
}
