package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.manager.init.OptionalReflectiveInitable;
import com.velocitypowered.api.proxy.ProxyServer;

final class VelocityLuckPermsInitable extends OptionalReflectiveInitable {
    private static final String HANDLER_CLASS = "ac.cult.cultac.platform.velocity.VelocityLuckPermsHandler";

    private final ProxyServer proxy;

    VelocityLuckPermsInitable(ProxyServer proxy) {
        super(HANDLER_CLASS, "Error when initializing LuckPerms hook");
        this.proxy = proxy;
    }

    @Override
    protected boolean isAvailable() {
        return proxy.getPluginManager().isLoaded("luckperms");
    }
}
