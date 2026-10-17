package ac.cult.cultac.companion.api;

import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertState;
import ac.cult.cultac.bridge.protocol.BridgeMessage.AlertToggle;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.alerts.AlertManager;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * Alert, verbose and brand toggles of proxy players. State follows ALERT_STATE; setters send ALERT_TOGGLE to the proxy
 * and update the local state at once. Players without a bridged session read as disabled and ignore setters.
 */
public final class CompanionAlertManager implements AlertManager {
    private final Function<UUID, CompanionUser> users;

    CompanionAlertManager(Function<UUID, CompanionUser> users) {
        this.users = users;
    }

    private CompanionUser resolve(GrimUser user) {
        if (user == null) return null;
        return user instanceof CompanionUser companion ? companion : users.apply(user.getUniqueId());
    }

    private CompanionUser resolve(Player player) {
        return player == null ? null : users.apply(player.getUniqueId());
    }

    private static boolean has(CompanionUser user, int bit) {
        return user != null && user.hasAlert(bit);
    }

    private static void set(CompanionUser user, int kind, int bit, boolean enabled, boolean silent) {
        if (user != null) user.toggleAlert(kind, bit, enabled, silent);
    }

    @Override
    public boolean hasAlertsEnabled(GrimUser user) {
        return has(resolve(user), AlertState.ALERTS);
    }

    @Override
    public void setAlertsEnabled(GrimUser user, boolean enabled, boolean silent) {
        set(resolve(user), AlertToggle.ALERTS, AlertState.ALERTS, enabled, silent);
    }

    @Override
    public boolean hasVerboseEnabled(GrimUser user) {
        return has(resolve(user), AlertState.VERBOSE);
    }

    @Override
    public void setVerboseEnabled(GrimUser user, boolean enabled, boolean silent) {
        set(resolve(user), AlertToggle.VERBOSE, AlertState.VERBOSE, enabled, silent);
    }

    @Override
    public boolean hasBrandsEnabled(GrimUser user) {
        return has(resolve(user), AlertState.BRANDS);
    }

    @Override
    public void setBrandsEnabled(GrimUser user, boolean enabled, boolean silent) {
        set(resolve(user), AlertToggle.BRANDS, AlertState.BRANDS, enabled, silent);
    }

    @Override
    public boolean hasAlertsEnabled(Player player) {
        return has(resolve(player), AlertState.ALERTS);
    }

    @Override
    public void toggleAlerts(Player player) {
        CompanionUser user = resolve(player);
        set(user, AlertToggle.ALERTS, AlertState.ALERTS, !has(user, AlertState.ALERTS), false);
    }

    @Override
    public boolean hasVerboseEnabled(Player player) {
        return has(resolve(player), AlertState.VERBOSE);
    }

    @Override
    public void toggleVerbose(Player player) {
        CompanionUser user = resolve(player);
        set(user, AlertToggle.VERBOSE, AlertState.VERBOSE, !has(user, AlertState.VERBOSE), false);
    }
}
