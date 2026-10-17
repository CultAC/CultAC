package ac.cult.cultac.platform.velocity.bridge.api;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.platform.api.scheduler.TaskHandle;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import ac.cult.cultac.platform.velocity.bridge.verdict.VerdictService;
import ac.cult.cultac.player.CultPlayer;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.plugin.GrimPlugin;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Starts the Grim API mirror; requires the bridge sessions to be installed in {@link VelocityBridge} first. */
public final class BridgeApiService {
    private static BridgeApiPublisher publisher;
    private static VerdictService verdicts;
    private static TaskHandle tick;

    private BridgeApiService() {}

    public static synchronized void start() {
        if (publisher != null) return;
        CultAPI api = CultAPI.INSTANCE;
        GrimPlugin plugin = api.getGrimPlugin();
        BridgeApiPublisher created = new BridgeApiPublisher(
                uuid -> api.getPlayerDataManager().getPlayer(uuid),
                api.getAlertManager(),
                api.getEventBus(),
                plugin,
                BridgeApiService::eventLoop,
                plugin.getLogger());
        created.subscribe();
        VelocityBridge.sessions().addListener(created);
        verdicts = VerdictService.create(
                uuid -> api.getPlayerDataManager().getPlayer(uuid), created::checkIndex, plugin.getLogger());
        created.flagOwnedBy(user -> user instanceof CultPlayer player && player.getFlagArbiter() != null);
        VelocityBridge.sessions().addListener(verdicts);
        tick = api.getScheduler().getAsyncScheduler().runAtFixedRate(plugin, created::tick, 1, 1, TimeUnit.SECONDS);
        publisher = created;
    }

    public static synchronized void stop() {
        if (publisher == null) return;
        tick.cancel();
        verdicts.close();
        publisher.close();
        publisher = null;
        verdicts = null;
        tick = null;
    }

    /** The CHECK_CATALOG index of {@code check} on the session's backend, or -1 when the mirror is not running. */
    public static synchronized int checkIndex(BridgeSession session, AbstractCheck check) {
        return publisher == null ? -1 : publisher.checkIndex(session, check);
    }

    private static Executor eventLoop(GrimUser user) {
        if (user instanceof CultPlayer player && player.user.getChannel() instanceof Channel channel) {
            EventLoop loop = channel.eventLoop();
            return task -> {
                if (loop.inEventLoop()) task.run();
                else loop.execute(task);
            };
        }
        return Runnable::run;
    }
}
