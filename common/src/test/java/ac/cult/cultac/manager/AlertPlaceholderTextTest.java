package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlertPlaceholderTextTest {
    private static final String FORMAT = EnglishDefaults.string("messages", "alerts-format") + " &8%brand%";

    private final ConfigManager config = mock(ConfigManager.class);
    private final CultPlayer player = mock(CultPlayer.class);

    AlertPlaceholderTextTest() {
        when(config.getBooleanElse(anyString(), anyBoolean())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getIntElse(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getDoubleElse(anyString(), anyDouble())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getStringElse(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @BeforeAll
    static void bootstrap() {
        OfflineCultTestBootstrap.installConfig();
    }

    /** The alert the staff see for one flag of {@code name} using {@code brand}. */
    private Component alert(String name, String brand) throws ReflectiveOperationException {
        return alert(FORMAT, name, brand, "verbose");
    }

    /** The alert {@code format} renders for one flag of {@code name} using {@code brand} with {@code verbose}. */
    private Component alert(String format, String name, String brand, String verbose)
            throws ReflectiveOperationException {
        flag(format, name, brand, verbose, "1:0 [alert]");
        var sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        return sent.getValue();
    }

    /** One flag of {@code name} using {@code brand} with {@code verbose}, running {@code commands} with {@code format}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void flag(String format, String name, String brand, String verbose, String... commands)
            throws ReflectiveOperationException {
        clearInvocations(player);
        when(player.getName()).thenReturn(name);
        when(player.getBrand()).thenReturn(brand);
        Check check = new Check(
                player,
                CheckInfo.builder()
                        .name("AlertTextProbe")
                        .stableKey("cult.test.alert_text_probe")
                        .build()) {};
        doReturn(List.of(check)).when(player).getChecks();
        when(config.getBooleanElse("test-mode", false)).thenReturn(true);
        when(config.getStringElse(eq("alerts-format"), anyString())).thenReturn(format);
        when(config.getStringElse(eq("alerts-format-proxy"), anyString())).thenReturn(format);
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(
                new LinkedHashMap<>(Map.of("checks", List.of("alerttextprobe"), "commands", List.of(commands)))));

        var external = CultAPI.INSTANCE.getExternalAPI();
        Map<String, Function<GrimUser, String>> registered = new LinkedHashMap<>();
        for (String key : List.of("%player%", "%brand%"))
            registered.put(key, external.getVariableReplacements().get(key));
        external.registerVariable("%player%", GrimUser::getName);
        external.registerVariable("%brand%", GrimUser::getBrand);
        try (var placeholders = new PlainPlaceholders()) {
            PunishmentManager punishments = new PunishmentManager(player);
            punishments.reload(config);
            punishments.handleViolation(check);
            punishments.handleAlert(player, verbose, check);
        } finally {
            registered.forEach(external::registerVariable);
        }
    }

    private static void collect(Component component, List<ClickEvent> clicks, List<String> insertions) {
        if (component.clickEvent() != null) clicks.add(component.clickEvent());
        if (component.insertion() != null) insertions.add(component.insertion());
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover)
            collect(hover, clicks, insertions);
        for (Component child : component.children()) collect(child, clicks, insertions);
    }

    private static List<String> hoverTexts(Component component) {
        List<String> texts = new ArrayList<>();
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover)
            texts.add(PlainTextComponentSerializer.plainText().serialize(hover));
        for (Component child : component.children()) texts.addAll(hoverTexts(child));
        return texts;
    }

    private static List<ClickEvent> clicks(Component component) {
        List<ClickEvent> clicks = new ArrayList<>();
        collect(component, clicks, new ArrayList<>());
        return clicks;
    }

    @Test
    void aBrandInAnAlertIsShownAsWritten() throws ReflectiveOperationException {
        String brand = "<click:run_command:'/cult debug channels Steve'><insert:x>&cvanilla %verbose%";
        Component alert = alert("Steve", brand);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(alert).endsWith(" " + brand));
        List<String> insertions = new ArrayList<>();
        List<ClickEvent> clicks = new ArrayList<>();
        collect(alert, clicks, insertions);
        assertEquals(List.of(), insertions);
        assertEquals(clicks(alert("Steve", "vanilla")), clicks);
    }

    @Test
    void anOrdinaryNameAndBrandRenderAsBefore() throws ReflectiveOperationException {
        Component alert = alert("Steve_01", "fabric (1.21.11) ünï");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(alert).contains("Steve_01 failed"));
        assertTrue(PlainTextComponentSerializer.plainText().serialize(alert).endsWith(" fabric (1.21.11) ünï"));
        assertTrue(clicks(alert).stream()
                .anyMatch(click -> click.action() == ClickEvent.Action.RUN_COMMAND
                        && click.toString().contains("spectate Steve_01")));
    }

    @Test
    void aNameInTheSpectateClickIsRunAsWritten() throws ReflectiveOperationException {
        String name = "St'e&v%<";
        Component alert = alert(name, "vanilla");
        assertTrue(clicks(alert).stream()
                .anyMatch(click -> click.action() == ClickEvent.Action.RUN_COMMAND
                        && click.toString().contains("spectate " + name)));
    }

    @Test
    void aBrandInsideAQuotedHoverArgumentStaysInsideIt() throws ReflectiveOperationException {
        String brand = "'><click:run_command:'/cult observe on";
        Component alert = alert("&f<hover:show_text:'Brand: %brand%'>%player%</hover>", "Steve", brand, "verbose");
        List<String> insertions = new ArrayList<>();
        List<ClickEvent> clicks = new ArrayList<>();
        collect(alert, clicks, insertions);
        assertEquals(List.of(), clicks);
        assertEquals(List.of(), insertions);
        assertEquals("Steve", PlainTextComponentSerializer.plainText().serialize(alert));
        assertEquals(List.of("Brand: " + brand), hoverTexts(alert));
    }

    @Test
    void aBrandAndVerboseTogetherAreShownAsWritten() throws ReflectiveOperationException {
        String brand = "<click:run_command:'/x";
        String verbose = "'>v";
        Component alert = alert("&f%brand% %verbose%", "Steve", brand, verbose);
        assertEquals(List.of(), clicks(alert));
        assertEquals(
                brand + " " + verbose, PlainTextComponentSerializer.plainText().serialize(alert));
    }

    /**
     * The alert lines {@code [proxy]} forwards for one flag; the commands the command event sees go to {@code fired},
     * and with a null {@code fired} the event has no listener.
     */
    private List<String> relayed(String format, String name, String brand, @Nullable List<String> fired)
            throws Exception {
        PlatformServer server = mock(PlatformServer.class);
        Field field = AlertManagerImpl.class.getDeclaredField("platformServer");
        field.setAccessible(true);
        Object original = field.get(null);
        GrimPlugin plugin = mock(GrimPlugin.class);
        if (fired != null)
            CultAPI.INSTANCE
                    .getEventBus()
                    .get(CommandExecuteEvent.class)
                    .onCommandExecuteSupplier(plugin, (user, flagged, verbose, command, cancelled) -> {
                        fired.add(command);
                        return cancelled;
                    });
        field.set(null, server);
        try {
            flag(format, name, brand, "verbose", "1:0 [alert]", "1:0 [proxy]");
        } finally {
            field.set(null, original);
            CultAPI.INSTANCE.getEventBus().unregisterAllListeners(plugin);
        }
        var forwarded = ArgumentCaptor.forClass(String.class);
        verify(server).forwardAlert(forwarded.capture());
        return forwarded.getAllValues();
    }

    @Test
    void theCommandEventAndARelayedAlertCarryTheBrandAsWritten() throws Exception {
        String brand = "<red>Lunar 50% x'y";
        List<String> fired = new ArrayList<>();
        List<String> relayed = relayed("&f%player% %brand%", "Steve", brand, fired);
        assertEquals(List.of("&fSteve " + brand, "&fSteve " + brand), fired);
        Component shown = MessageUtil.miniMessage(relayed.getFirst());
        assertEquals("Steve " + brand, PlainTextComponentSerializer.plainText().serialize(shown));
        assertEquals(List.of(), clicks(shown));
    }

    @Test
    void aRelayedAlertShowsColourCodesInTheBrandAsWritten() throws Exception {
        String brand = "&rRESET &kOBF &#00FF00hex %prefix%";
        Component shown = MessageUtil.miniMessage(
                relayed("&f%player% %brand%", "Steve", brand, null).getFirst());
        assertEquals("Steve " + brand, PlainTextComponentSerializer.plainText().serialize(shown));
        String json = GsonComponentSerializer.gson().serialize(shown);
        assertFalse(json.contains("\"obfuscated\":true") || json.contains("#00ff00"), json);
    }
}
