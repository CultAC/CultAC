package ac.cult.cultac.platform.velocity.bridge.command;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Serves backend-forwarded commands and cross-server spectating; requires the bridge sessions to be installed in
 * {@link VelocityBridge}.
 */
public final class BridgeCommandService {
    private static BridgeCommandHandler commands;
    private static BridgeSpectateRouter spectate;

    private BridgeCommandService() {}

    /** @param plugin the Velocity plugin instance that registers CultAC's command roots */
    public static synchronized void start(ProxyServer proxy, Object plugin, Logger logger) {
        if (commands != null) return;
        BridgeSessions sessions = VelocityBridge.sessions();
        CommandManager manager = proxy.getCommandManager();
        commands = new BridgeCommandHandler(
                manager,
                CloudCommandService::roots,
                ownedRoots(manager, plugin),
                id -> proxy.getPlayer(id).orElse(null),
                System::nanoTime,
                logger);
        spectate = new BridgeSpectateRouter(
                proxy,
                sessions,
                BridgeSpectateRouter.READY_TIMEOUT_MILLIS,
                BridgeSpectateRouter.READY_TIMEOUT_MILLIS + BridgeSpectateRouter.CONNECT_MARGIN_MILLIS);
        sessions.addListener(commands);
        sessions.addListener(spectate);
        CultAPI.INSTANCE.getSpectateManager().setRouter(spectate);
    }

    /** A root is CultAC's while the meta registered under it names CultAC's plugin instance. */
    static Predicate<String> ownedRoots(CommandManager manager, Object plugin) {
        return root -> {
            CommandMeta meta = manager.getCommandMeta(root);
            return meta != null && meta.getPlugin() == plugin;
        };
    }

    public static synchronized void stop() {
        if (commands == null) return;
        CultAPI.INSTANCE.getSpectateManager().setRouter(null);
        commands.close();
        spectate.close();
        commands = null;
        spectate = null;
    }
}
