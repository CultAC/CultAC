package ac.cult.velocity;

import ac.cult.cultac.platform.velocity.VelocityPlatform;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.slf4j.Logger;

@Plugin(
        id = "cultac",
        name = "CultAC",
        version = "0.1.0",
        description = "Compensated movement anticheat",
        authors = {"GrimCult"},
        dependencies = {
            @Dependency(id = "geyser", optional = true),
            @Dependency(id = "floodgate", optional = true),
            @Dependency(id = "viaversion", optional = true),
            @Dependency(id = "viabackwards", optional = true)
        })
public final class CultVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private VelocityPlatform engine;

    @Inject
    public CultVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    // Via loads its manager at NORMAL and enables conversion at LAST. Optional
    // dependencies register its LAST handler before ours when it is installed.
    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent event) throws Exception {
        try {
            engine = new VelocityPlatform(proxy, this, directory, logger);
            engine.start();
        } catch (Throwable failure) {
            if (engine != null)
                try {
                    engine.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            engine = null;
            proxy.shutdown(net.kyori.adventure.text.Component.text("CultAC failed to initialize. See the proxy log."));
            throw failure;
        }
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) throws Exception {
        if (engine != null) engine.close();
    }
}
