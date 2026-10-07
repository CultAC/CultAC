package ac.cult.cultac.network;

import ac.cult.cultac.CultAPI;

/** Actual consumer fixtures need loaded messages and platform services without a running plugin. */
final class RecordConsumerServices implements AutoCloseable {
    private final java.lang.reflect.Field network = CultAPI.class.getDeclaredField("networkManager");
    private final CultNetworkManager originalNetwork;
    private final java.lang.reflect.Field loader = CultAPI.class.getDeclaredField("loader");
    private final java.lang.reflect.Field dataStore = CultAPI.class.getDeclaredField("dataStoreLifecycle");
    private final java.lang.reflect.Field config = CultAPI.class.getDeclaredField("configManager");
    private final Object originalLoader;
    private final Object originalStore;
    private final ac.cult.cultac.manager.config.BaseConfigManager originalConfig;

    RecordConsumerServices() throws Exception {
        ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.installConfig();
        network.setAccessible(true);
        originalNetwork = CultAPI.INSTANCE.getNetworkManager();
        loader.setAccessible(true);
        dataStore.setAccessible(true);
        config.setAccessible(true);
        originalLoader = loader.get(CultAPI.INSTANCE);
        originalStore = dataStore.get(CultAPI.INSTANCE);
        originalConfig = CultAPI.INSTANCE.getConfigManager();
        var loadedConfig = new ac.cult.cultac.manager.config.BaseConfigManager();
        loadedConfig.load(originalConfig.getConfig());
        config.set(CultAPI.INSTANCE, loadedConfig);
        ac.cult.cultac.platform.api.manager.MessagePlaceHolderManager placeholders = (player, text) -> text;
        loader.set(
                CultAPI.INSTANCE,
                java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ac.cult.cultac.platform.api.PlatformLoader.class},
                        (proxy, method, args) -> method.getName().equals("getMessagePlaceHolderManager")
                                ? placeholders
                                : method.invoke(originalLoader, args)));
        dataStore.set(
                CultAPI.INSTANCE,
                new ac.cult.cultac.manager.datastore.DataStoreLifecycle(
                        CultAPI.INSTANCE.getGrimPlugin(), CultAPI.INSTANCE.getBackendRegistry()));
    }

    RecordConsumerServices(ac.cult.cultac.protocol.ProtocolVersion version) throws Exception {
        this();
        var manager = new CultNetworkManager();
        manager.configureTransport(
                TestProtocolRuntime.create(ac.cult.cultac.protocol.data.ProtocolData.load(version)),
                () -> {},
                () -> java.util.concurrent.CompletableFuture.completedFuture(null));
        network.set(CultAPI.INSTANCE, manager);
    }

    /** Checks and transport must bind the same platform codec instances, as they do in production. */
    void useNetwork(CultNetworkManager manager) throws IllegalAccessException {
        network.set(CultAPI.INSTANCE, manager);
    }

    @Override
    public void close() throws Exception {
        network.set(CultAPI.INSTANCE, originalNetwork);
        loader.set(CultAPI.INSTANCE, originalLoader);
        dataStore.set(CultAPI.INSTANCE, originalStore);
        config.set(CultAPI.INSTANCE, originalConfig);
    }
}
