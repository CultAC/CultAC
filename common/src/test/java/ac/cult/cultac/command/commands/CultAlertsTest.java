package ac.cult.cultac.command.commands;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.manager.datastore.DataStoreLifecycle;
import ac.cult.cultac.manager.datastore.PlayerToggleStore;
import ac.cult.cultac.manager.datastore.PlayerToggleStoreImpl;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.grim.grimac.api.storage.DataStore;
import ac.grim.grimac.api.storage.category.Categories;
import ac.grim.grimac.api.storage.event.SettingEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.incendo.cloud.context.CommandContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class CultAlertsTest {
    @Test
    @SuppressWarnings("unchecked")
    public void disablingAlertsPersistsVerboseOffAcrossReconnect() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultAPI api = CultAPI.INSTANCE;
        AlertManagerImpl alerts = api.getAlertManager();
        UUID uuid = UUID.fromString("337c4791-b73b-4460-8afc-05ad3f036efd");
        PlatformPlayer player = mock(PlatformPlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        Sender sender = mock(Sender.class);
        when(sender.isPlayer()).thenReturn(true);
        when(sender.getPlatformPlayer()).thenReturn(player);
        CommandContext<Sender> context = mock(CommandContext.class);
        when(context.sender()).thenReturn(sender);

        Map<String, Boolean> persisted = new ConcurrentHashMap<>();
        DataStore dataStore = mock(DataStore.class);
        doAnswer(invocation -> {
            SettingEvent event = new SettingEvent();
            invocation.<Consumer<SettingEvent>>getArgument(1).accept(event);
            persisted.put(event.scopeKey() + "/" + event.key(), event.value()[0] != 0);
            return null;
        }).when(dataStore).submit(eq(Categories.SETTING), any());

        Field lifecycleField = CultAPI.class.getDeclaredField("dataStoreLifecycle");
        lifecycleField.setAccessible(true);
        Object previousLifecycle = lifecycleField.get(api);
        DataStoreLifecycle lifecycle = new DataStoreLifecycle(api.getGrimPlugin(), api.getBackendRegistry());
        Field togglesField = DataStoreLifecycle.class.getDeclaredField("playerToggleStore");
        togglesField.setAccessible(true);
        Class<?> alertType = Class.forName(AlertManagerImpl.class.getName() + "$AlertType");
        Field disabledMessage = alertType.getDeclaredField("disableMessage");
        disabledMessage.setAccessible(true);
        Map<Object, Object> previousMessages = new LinkedHashMap<>();
        for (Object type : alertType.getEnumConstants()) {
            if (((Enum<?>) type).name().equals("NORMAL") || ((Enum<?>) type).name().equals("VERBOSE")) {
                previousMessages.put(type, disabledMessage.get(type));
            }
        }

        PlayerToggleStoreImpl toggles = new PlayerToggleStoreImpl(dataStore, Logger.getAnonymousLogger());
        try {
            togglesField.set(lifecycle, toggles);
            lifecycleField.set(api, lifecycle);
            for (Object type : previousMessages.keySet()) disabledMessage.set(type, "");
            alerts.setVerboseEnabled(player, true, true);
            toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_ALERTS, true);
            toggles.applyUserToggle(uuid, PlayerToggleStore.KEY_VERBOSE, true);
            toggles.evict(uuid);

            Method handler = CultAlerts.class.getDeclaredMethod("handleAlerts", CommandContext.class);
            handler.setAccessible(true);
            handler.invoke(new CultAlerts(), context);

            assertFalse(alerts.hasAlertsEnabled(player));
            assertFalse(alerts.hasVerboseEnabled(player));
            toggles.evict(uuid);
            assertEquals(Boolean.FALSE, persisted.get(uuid + "/" + PlayerToggleStore.KEY_ALERTS));
            assertEquals(Boolean.FALSE, persisted.get(uuid + "/" + PlayerToggleStore.KEY_VERBOSE));

            alerts.handlePlayerQuit(player);
            alerts.setAlertsEnabled(player, persisted.get(uuid + "/" + PlayerToggleStore.KEY_ALERTS), true);
            alerts.setVerboseEnabled(player, persisted.get(uuid + "/" + PlayerToggleStore.KEY_VERBOSE), true);
            assertFalse(alerts.hasAlertsEnabled(player));
            assertFalse(alerts.hasVerboseEnabled(player));
        } finally {
            alerts.handlePlayerQuit(player);
            toggles.shutdown();
            lifecycleField.set(api, previousLifecycle);
            for (Map.Entry<Object, Object> previous : previousMessages.entrySet()) {
                disabledMessage.set(previous.getKey(), previous.getValue());
            }
        }
    }
}
