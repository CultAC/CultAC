package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.luckperms.AbstractLuckPermsHandler;
import ac.cult.cultac.platform.luckperms.LuckPermsRetry;
import ac.cult.cultac.runtime.RuntimeEnvironment;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.LuckPermsEvent;
import net.luckperms.api.event.context.ContextUpdateEvent;

/// @see VelocityLuckPermsInitable
public final class VelocityLuckPermsHandler extends AbstractLuckPermsHandler implements StartableInitable {

    private final LuckPermsRetry retry = new LuckPermsRetry(
            this::tryRegister,
            (task, seconds) -> {
                TaskHandle handle = CultAPI.INSTANCE
                        .getScheduler()
                        .getAsyncScheduler()
                        .runDelayed(CultAPI.INSTANCE.getGrimPlugin(), task, seconds, TimeUnit.SECONDS);
                return handle::cancel;
            },
            task -> CultAPI.INSTANCE.getPortalSync().onNextJoin(task),
            LogUtil::warn);

    @Override
    public void start() {
        retry.start();
    }

    @Override
    public void stop() {
        retry.stop();
        super.stop();
    }

    /** False while LuckPerms' API is not up, as when a swapped-in runtime starts before LuckPerms enables. */
    private boolean tryRegister() {
        LuckPerms luckPerms;
        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException notYet) {
            return false;
        }
        try {
            register(luckPerms);
        } catch (Exception e) {
            LogUtil.error("Error when initializing LuckPerms hook", e);
        }
        return true;
    }

    @Override
    protected <T extends LuckPermsEvent> EventSubscription<T> subscribe(
            LuckPerms luckPerms, Class<T> eventClass, Consumer<? super T> handler) {
        var subscription = luckPerms.getEventBus().subscribe(eventClass, handler);
        return RuntimeEnvironment.track(subscription, subscription::close);
    }

    @Override
    protected UUID contextSubjectUuid(ContextUpdateEvent event) {
        return event.getSubject(Player.class).map(Player::getUniqueId).orElse(null);
    }
}
