package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.AlertManagerImpl;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.config.ConfigManager;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.Mockito;

/** Staff who see a player's Cult Log identifiers, as a server shows them. */
public final class CultLogViewers {
    /** The shipped Simulation group's alert and log commands, as the config loader reads them. */
    private static final List<Map<String, List<String>>> SIMULATION_ALERTS = List.of(new LinkedHashMap<>(
            Map.of("checks", List.of("Simulation"), "commands", List.of("100:40 [alert]", "1:1 [log]"))));

    private CultLogViewers() {}

    /**
     * Until closed, {@code player}'s Simulation flags alert as shipped and a staff member has /cult verbose on, so each
     * Simulation alert, Cult Log identifier included, is rendered for them. A platform without placeholders gets ones
     * that leave text as it is, since an alert is only shown once its placeholders are filled.
     */
    public static AutoCloseable watch(CultPlayer player) throws ReflectiveOperationException {
        Field loaderField = CultAPI.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        Object loader = loaderField.get(CultAPI.INSTANCE);
        boolean placeholders = loader != null && ((PlatformLoader) loader).getMessagePlaceHolderManager() != null;
        if (!placeholders) loaderField.set(CultAPI.INSTANCE, withPlaceholders(loader));

        ConfigManager config = CultAPI.INSTANCE.getConfigManager().getConfig();
        player.punishmentManager.reload(withPunishments(config));
        AlertManagerImpl alerts = CultAPI.INSTANCE.getAlertManager();
        PlatformPlayer staff = Mockito.mock(PlatformPlayer.class);
        Mockito.when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(staff.getNative()).thenReturn(new Object());
        alerts.setVerboseEnabled(staff, true, true);
        return () -> {
            alerts.handlePlayerQuit(staff);
            player.punishmentManager.reload(config);
            if (!placeholders) loaderField.set(CultAPI.INSTANCE, loader);
        };
    }

    private static ConfigManager withPunishments(ConfigManager config) {
        return (ConfigManager) Proxy.newProxyInstance(
                ConfigManager.class.getClassLoader(),
                new Class<?>[] {ConfigManager.class},
                (proxy, method, args) -> method.getName().equals("getStringListElse") && "Punishments".equals(args[0])
                        ? SIMULATION_ALERTS
                        : method.invoke(config, args));
    }

    private static PlatformLoader withPlaceholders(Object loader) {
        MessagePlaceHolderManager placeholders = (player, text) -> text;
        return (PlatformLoader) Proxy.newProxyInstance(
                PlatformLoader.class.getClassLoader(),
                new Class<?>[] {PlatformLoader.class},
                (proxy, method, args) -> method.getName().equals("getMessagePlaceHolderManager")
                        ? placeholders
                        : method.invoke(loader, args));
    }
}
