package ac.cult.cultac.platform.bukkit.initables;

import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.bukkit.CultACBukkitLoaderPlugin;
import ac.cult.cultac.platform.luckperms.AbstractLuckPermsHandler;
import ac.cult.cultac.utils.anticheat.LogUtil;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.LuckPermsEvent;
import net.luckperms.api.event.context.ContextUpdateEvent;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Consumer;

/// @see BukkitLuckPermsInitable
public class BukkitLuckPermsHandler extends AbstractLuckPermsHandler implements StartableInitable {

    @Override
    public void start() {
        try {
            register(LuckPermsProvider.get());
        } catch (IllegalStateException e) {
            LogUtil.warn("LuckPerms detected but its API was not available for the permission refresh hook");
        } catch (Exception e) {
            LogUtil.error("Error when initializing LuckPerms hook", e);
        }
    }

    @Override
    protected <T extends LuckPermsEvent> EventSubscription<T> subscribe(
            LuckPerms luckPerms,
            Class<T> eventClass,
            Consumer<? super T> handler
    ) {
        return luckPerms.getEventBus().subscribe(CultACBukkitLoaderPlugin.LOADER, eventClass, handler);
    }

    @Override
    protected UUID contextSubjectUuid(ContextUpdateEvent event) {
        return event.getSubject(Player.class)
                .map(Player::getUniqueId)
                .orElse(null);
    }
}
