package ac.cult.cultac.command.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.alerts.AlertScope;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CultAlertsScopeTest {
    private final AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
    private final PlatformPlayer player = mock(PlatformPlayer.class);
    private final Sender sender = mock(Sender.class);
    private Field loader;
    private Object originalLoader;

    @BeforeAll
    static void config() {
        OfflineCultTestBootstrap.installConfig();
    }

    CultAlertsScopeTest() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getNative()).thenReturn(new Object());
        when(sender.isPlayer()).thenReturn(true);
        when(sender.getPlatformPlayer()).thenReturn(player);
    }

    @BeforeEach
    void placeholders() throws ReflectiveOperationException {
        loader = CultAPI.class.getDeclaredField("loader");
        loader.setAccessible(true);
        originalLoader = loader.get(CultAPI.INSTANCE);
        MessagePlaceHolderManager placeholders = (player, text) -> text;
        loader.set(
                CultAPI.INSTANCE,
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {PlatformLoader.class},
                        (proxy, method, args) -> method.getName().equals("getMessagePlaceHolderManager")
                                ? placeholders
                                : method.invoke(originalLoader, args)));
    }

    @AfterEach
    void quit() throws ReflectiveOperationException {
        alerts.handlePlayerQuit(player);
        loader.set(CultAPI.INSTANCE, originalLoader);
    }

    private String reply() {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(message.capture());
        return GsonComponentSerializer.gson().serialize(message.getValue());
    }

    @Test
    void globalWithoutATransportIsRefusedAndChangesNothing() {
        CultAlerts.chooseScope(sender, AlertScope.GLOBAL);
        assertTrue(reply().contains("Network-wide alerts are not enabled on this server"));
        assertNull(alerts.chosenScope(player));
    }

    @Test
    void serverIsChosenAndTheScopeNamesWhereItComesFrom() {
        CultAlerts.chooseScope(sender, AlertScope.SERVER);
        assertTrue(reply().contains("Alert scope set to "));
        assertEquals(AlertScope.SERVER, alerts.chosenScope(player));

        Sender asking = mock(Sender.class);
        when(asking.isPlayer()).thenReturn(true);
        when(asking.getPlatformPlayer()).thenReturn(player);
        CultAlerts.showScope(asking);
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(asking).sendMessage(message.capture());
        String shown = GsonComponentSerializer.gson().serialize(message.getValue());
        assertTrue(shown.contains("server"), shown);
        assertTrue(shown.contains("network-wide alerts are not enabled on this server"), shown);
    }

    @Test
    void theConsoleHasNoScope() {
        when(sender.isPlayer()).thenReturn(false);
        CultAlerts.chooseScope(sender, AlertScope.SERVER);
        assertTrue(reply().contains("only be used by players"));
        assertNull(alerts.chosenScope(player));
    }
}
