package ac.cult.cultac.utils.anticheat;

import ac.cult.cultac.CultAPI;
import ac.grim.grimac.api.GrimUser;
import ac.cult.cultac.player.CultPlayer;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Presentation helpers turning raw config strings and components into text a
 * human reader can consume: prefix substitution, legacy color-code translation,
 * per-user placeholder resolution, and dispatch to a command sender.
 */
@UtilityClass
public class HumanFormatter {

    /** Substitutes the configured prefix, then translates &-style color codes. */
    public String format(String input) {
        String substituted = formatWithNoColor(input);
        return ChatColor.translateAlternateColorCodes('&', substituted);
    }

    /** Substitutes the configured prefix without touching color codes. */
    public String formatWithNoColor(String input) {
        String prefix = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse("prefix", "&bCult &8»");
        return input.replace("%prefix%", prefix);
    }

    /** Resolves user-scoped placeholders when the user is an in-game player. */
    public Component format(GrimUser user, Component component) {
        return user instanceof CultPlayer cultPlayer
                ? MessageUtil.replacePlaceholders(cultPlayer, component)
                : component;
    }

    /** Without a user there is no context to resolve placeholders against. */
    public Component format(Component component) {
        return component;
    }

    /** Sends the component to the sender, players and console alike. */
    public void message(CommandSender sender, Component component) {
        if (sender instanceof Player player) {
            player.sendMessage(component);
        } else {
            sender.sendMessage(component);
        }
    }
}
