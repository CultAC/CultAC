package ac.cult.cultac.platform.velocity;

import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.platform.api.sender.SenderFactory;
import ac.cult.cultac.platform.velocity.bridge.command.RelayCommandSource;
import ac.cult.cultac.utils.anticheat.LogUtil;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class VelocitySenders extends SenderFactory<CommandSource> {
    private final ProxyServer proxy;
    private final VelocityPermissions permissions;

    VelocitySenders(ProxyServer proxy, VelocityPermissions permissions) {
        this.proxy = proxy;
        this.permissions = permissions;
    }

    @Override
    protected UUID getUniqueId(CommandSource source) {
        return source instanceof Player player ? player.getUniqueId() : Sender.CONSOLE_UUID;
    }

    @Override
    protected String getName(CommandSource source) {
        return source instanceof Player player ? player.getUsername() : Sender.CONSOLE_NAME;
    }

    @Override
    protected void sendMessage(CommandSource source, String message) {
        source.sendMessage(LegacyComponentSerializer.legacySection().deserialize(message));
    }

    @Override
    protected void sendMessage(CommandSource source, Component message) {
        source.sendMessage(message);
    }

    @Override
    protected boolean hasPermission(CommandSource source, String node) {
        return permissions.has(source, node);
    }

    @Override
    protected boolean hasPermission(CommandSource source, String node, boolean fallback) {
        return VelocityPermissions.has(source, node, fallback);
    }

    @Override
    protected void performCommand(CommandSource source, String command) {
        proxy.getCommandManager().executeAsync(source, command).whenComplete((success, failure) -> {
            if (failure != null) {
                LogUtil.error("Proxy command failed", failure);
            }
        });
    }

    @Override
    protected boolean isConsole(CommandSource source) {
        return source == proxy.getConsoleCommandSource() || source instanceof RelayCommandSource;
    }

    @Override
    protected boolean isPlayer(CommandSource source) {
        return source instanceof Player;
    }
}
