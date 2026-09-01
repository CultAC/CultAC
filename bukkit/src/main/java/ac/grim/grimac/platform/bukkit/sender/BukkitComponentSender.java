package ac.grim.grimac.platform.bukkit.sender;

import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

public final class BukkitComponentSender {
    private BukkitComponentSender() {}

    public static void sendMessage(CommandSender sender, Component message) {
        // Paper exposes CommandSender as a native Adventure Audience. Prefer the
        // server's own serializer so the Component and serializer versions match.
        // The Object cast keeps this a runtime check when compiling against Paper.
        if ((Object) sender instanceof Audience audience) {
            audience.sendMessage(message);
            return;
        }

        // Older Bukkit/Spigot has no native Audience. Load the compatibility
        // adapter only on that path; loading it on Adventure 5.x is unsafe because
        // adventure-platform-bukkit 4.x targets the Adventure 4.x binary API.
        LegacyAudiences.INSTANCE.sender(sender).sendMessage(message);
    }

    private static final class LegacyAudiences {
        private static final BukkitAudiences INSTANCE = BukkitAudiences.create(GrimACBukkitLoaderPlugin.LOADER);
    }
}
