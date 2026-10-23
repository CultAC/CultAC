package ac.cult.cultac.platform.bukkit;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CheckPermissions;
import ac.cult.cultac.cloud.CloudModule;
import ac.cult.cultac.command.CloudCommandService;
import ac.cult.cultac.command.commands.CultLabelMenu;
import ac.cult.cultac.manager.init.Initable;
import ac.cult.cultac.manager.init.start.StartableInitable;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.platform.api.PlatformLoader;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.platform.api.command.CommandService;
import ac.cult.cultac.platform.api.manager.ItemResetHandler;
import ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager;
import ac.cult.cultac.platform.api.manager.PlatformPluginManager;
import ac.cult.cultac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.cult.cultac.platform.api.player.PlatformPlayerFactory;
import ac.cult.cultac.platform.api.scheduler.PlatformScheduler;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.api.sender.SenderFactory;
import ac.cult.cultac.platform.bukkit.cloud.BukkitCloudFacts;
import ac.cult.cultac.platform.bukkit.initables.BukkitEventManager;
import ac.cult.cultac.platform.bukkit.initables.BukkitLuckPermsInitable;
import ac.cult.cultac.platform.bukkit.manager.BukkitCloudPlatformCommandArguments;
import ac.cult.cultac.platform.bukkit.manager.BukkitItemResetHandler;
import ac.cult.cultac.platform.bukkit.manager.BukkitMessagePlaceHolderManager;
import ac.cult.cultac.platform.bukkit.manager.BukkitPermissionRegistrationManager;
import ac.cult.cultac.platform.bukkit.manager.BukkitPlatformPluginManager;
import ac.cult.cultac.platform.bukkit.player.BukkitPlatformPlayerFactory;
import ac.cult.cultac.platform.bukkit.scheduler.bukkit.BukkitPlatformScheduler;
import ac.cult.cultac.platform.bukkit.scheduler.folia.FoliaPlatformScheduler;
import ac.cult.cultac.platform.bukkit.sender.BukkitSenderFactory;
import ac.cult.cultac.platform.bukkit.utils.placeholder.PlaceholderAPIExpansion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.LogUtil;
import ac.cult.cultac.utils.lazy.LazyHolder;
import ac.grim.grimac.api.plugin.GrimPlugin;
import ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.brigadier.parser.WrappedBrigadierParser;
import org.incendo.cloud.bukkit.BukkitCommandContextKeys;
import org.incendo.cloud.bukkit.internal.BukkitBackwardsBrigadierSenderMapper;
import org.incendo.cloud.bukkit.internal.BukkitHelper;

public final class CultACBukkitLoaderPlugin implements PlatformLoader {
    public static JavaPlugin LOADER;
    public static CultACBukkitLoaderPlugin INSTANCE;
    private PaperInjector injector;

    private final LazyHolder<PlatformScheduler> scheduler = LazyHolder.simple(this::createScheduler);
    private final LazyHolder<BukkitSenderFactory> senderFactory = LazyHolder.simple(BukkitSenderFactory::new);
    private final LazyHolder<ItemResetHandler> itemResetHandler = LazyHolder.simple(BukkitItemResetHandler::new);
    private final LazyHolder<CommandService> commandService = LazyHolder.simple(this::createCommandService);
    private final CloudPlatformCommandArguments commandArguments = new BukkitCloudPlatformCommandArguments();

    @Getter
    private final PlatformPlayerFactory platformPlayerFactory = new BukkitPlatformPlayerFactory();

    @Getter
    private final PlatformPluginManager pluginManager = new BukkitPlatformPluginManager();

    @Getter
    private final GrimPlugin plugin;

    @Getter
    private final PlatformServer platformServer = new BukkitPlatformServer();

    @Getter
    private final MessagePlaceHolderManager messagePlaceHolderManager = new BukkitMessagePlaceHolderManager();

    @Getter
    private final BukkitPermissionRegistrationManager permissionManager = new BukkitPermissionRegistrationManager();

    public CultACBukkitLoaderPlugin(JavaPlugin nativePlugin) {
        LOADER = nativePlugin;
        INSTANCE = this;
        BukkitResolverRegistrar registrar = new BukkitResolverRegistrar();
        var host = ac.cult.cultac.runtime.RuntimeEnvironment.host();
        if (host == null) registrar.registerAll(CultAPI.INSTANCE.getExtensionManager());
        this.plugin = host == null
                ? registrar.resolvePlugin(nativePlugin)
                : ac.cult.reload.host.SharedPlugin.create(host.directory(), host.logger(), host.build());
    }

    @Override
    public boolean failOnInitializationError() {
        return true;
    }

    public void onLoad() {
        injector = BukkitProtocolTransport.initialize(CultAPI.INSTANCE.getNetworkManager(), LOADER);
        CultAPI.INSTANCE.load(this, this.getBukkitInitTasks());
    }

    private Initable[] getBukkitInitTasks() {
        return new Initable[] {
            new ac.cult.cultac.manager.init.start.TickEndEvent(),
            new ac.cult.cultac.manager.init.start.ChannelManager(),
            new ac.cult.cultac.manager.init.start.GeyserBedrockBridgeInit(),
            (StartableInitable) ac.cult.cultac.events.packets.ProxyAlertMessenger::new,
            new BukkitEventManager(),
            new BukkitLuckPermsInitable(),
            (StartableInitable) this::registerCheckPermissions,
            (StartableInitable) () -> {
                if (BukkitMessagePlaceHolderManager.hasPlaceholderAPI) {
                    if (ac.cult.cultac.runtime.RuntimeEnvironment.host() == null)
                        new PlaceholderAPIExpansion().register();
                }
            }
        };
    }

    // Bukkit's permission registry is not thread-safe; a runtime swapped in while the server runs starts elsewhere.
    private void registerCheckPermissions() {
        if (Bukkit.isPrimaryThread()) CheckPermissions.register(permissionManager);
        else
            getScheduler().getGlobalRegionScheduler().run(plugin, () -> {
                CheckPermissions.register(permissionManager);
                for (CultPlayer online : CultAPI.INSTANCE.getPlayerDataManager().getEntries()) {
                    try {
                        online.runSafely(online::updatePermissions);
                    } catch (RejectedExecutionException closing) {
                        // A connection whose owner is shutting down has no session left to exempt.
                    }
                }
            });
    }

    public void onEnable() {
        CloudModule.platformHooks(() -> BukkitCloudFacts.turnedOn(LOADER), BukkitCloudFacts::turnedOff);
        CultLabelMenu.install();
        CultAPI.INSTANCE.start();
    }

    public void onDisable() {
        if (CultAPI.INSTANCE.isInitialized()) CultAPI.INSTANCE.stop();
        else CultAPI.INSTANCE.getNetworkManager().stop().toCompletableFuture().join();
    }

    @Override
    public PlatformScheduler getScheduler() {
        return scheduler.get();
    }

    @Override
    public ItemResetHandler getItemResetHandler() {
        return itemResetHandler.get();
    }

    @Override
    public CommandService getCommandService() {
        return commandService.get();
    }

    @Override
    public SenderFactory<CommandSender> getSenderFactory() {
        return senderFactory.get();
    }

    @Override
    @SuppressWarnings("removal")
    public void registerAPIService() {
        // The bootstrap registers the stable GrimAPI service exactly once.
    }

    public void inject(io.netty.channel.Channel channel) {
        injector.injectNew(channel);
    }

    public java.util.concurrent.CompletionStage<Void> disconnect(io.netty.channel.Channel channel) {
        return injector.disconnect(channel);
    }

    public java.io.File getDataFolder() {
        return LOADER.getDataFolder();
    }

    private PlatformScheduler createScheduler() {
        return CultAPI.INSTANCE.getPlatform() == Platform.FOLIA
                ? new FoliaPlatformScheduler()
                : new BukkitPlatformScheduler();
    }

    private CommandService createCommandService() {
        try {
            return new CloudCommandService(this::createCloudCommandManager, commandArguments);
        } catch (Throwable t) {
            if (ac.cult.cultac.runtime.RuntimeEnvironment.host() != null)
                throw new IllegalStateException("Unable to initialize CultAC commands", t);
            LogUtil.warn(
                    "CRITICAL: Failed to initialize Command Framework. "
                            + "Cult will continue to run with no commands.",
                    t);
            return () -> {};
        }
    }

    private CommandManager<Sender> createCloudCommandManager() {
        var manager = new ac.cult.cultac.command.IsolatedCommandManager();
        var nativeSenderMapper = new BukkitBackwardsBrigadierSenderMapper<>(senderFactory.get());
        // Preserve the selector context normally supplied by LegacyPaperCommandManager.
        manager.registerCommandPreProcessor(context -> {
            var commandContext = context.commandContext();
            var sender = commandContext.sender();
            var nativeSender = senderFactory.get().reverse(sender);
            commandContext.store(BukkitCommandContextKeys.BUKKIT_COMMAND_SENDER, nativeSender);
            commandContext.store(
                    WrappedBrigadierParser.COMMAND_CONTEXT_BRIGADIER_NATIVE_SENDER, nativeSenderMapper.apply(sender));
            commandContext.store(BukkitCommandContextKeys.SENDER_SCHEDULER_EXECUTOR, commandExecutor(nativeSender));
        });
        return manager;
    }

    private Executor commandExecutor(CommandSender sender) {
        if (CultAPI.INSTANCE.getPlatform() != Platform.FOLIA) {
            return BukkitHelper.mainThreadExecutor(() -> LOADER);
        }
        if (sender instanceof Entity entity) {
            return task -> entity.getScheduler().run(LOADER, handle -> task.run(), null);
        }
        if (sender instanceof BlockCommandSender blockSender) {
            return task -> blockSender
                    .getServer()
                    .getRegionScheduler()
                    .run(LOADER, blockSender.getBlock().getLocation(), handle -> task.run());
        }
        return task -> LOADER.getServer().getGlobalRegionScheduler().run(LOADER, handle -> task.run());
    }

    public BukkitSenderFactory getBukkitSenderFactory() {
        return senderFactory.get();
    }
}
