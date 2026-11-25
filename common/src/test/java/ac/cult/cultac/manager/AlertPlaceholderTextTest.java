package ac.cult.cultac.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.manager.config.EnglishDefaults;
import ac.cult.cultac.network.routing.PlainPlaceholders;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.config.ConfigManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component alert(String name, String brand) throws ReflectiveOperationException {
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
        when(config.getStringElse(eq("alerts-format"), anyString())).thenReturn(FORMAT);
        when(config.getStringListElse(eq("Punishments"), any())).thenReturn((List) List.of(
                new LinkedHashMap<>(Map.of("checks", List.of("alerttextprobe"), "commands", List.of("1:0 [alert]")))));

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
            punishments.handleAlert(player, "verbose", check);
        } finally {
            registered.forEach(external::registerVariable);
        }
        var sent = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(sent.capture());
        return sent.getValue();
    }

    private static void collect(Component component, List<ClickEvent> clicks, List<String> insertions) {
        if (component.clickEvent() != null) clicks.add(component.clickEvent());
        if (component.insertion() != null) insertions.add(component.insertion());
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover)
            collect(hover, clicks, insertions);
        for (Component child : component.children()) collect(child, clicks, insertions);
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
}
