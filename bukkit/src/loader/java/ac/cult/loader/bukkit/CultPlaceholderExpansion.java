package ac.cult.loader.bukkit;

import ac.cult.reload.host.GenerationManager;
import ac.grim.grimac.api.GrimAbstractAPI;
import java.util.List;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class CultPlaceholderExpansion extends PlaceholderExpansion {
    private final CultACBootstrapPlugin plugin;
    private final GenerationManager runtimes;

    CultPlaceholderExpansion(CultACBootstrapPlugin plugin, GenerationManager runtimes) {
        this.plugin = plugin;
        this.runtimes = runtimes;
    }

    public @NotNull String getIdentifier() {
        return "cult";
    }

    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    public @NotNull String getVersion() {
        return ((GrimAbstractAPI) runtimes.publicApi()).getGrimVersion();
    }

    public boolean persist() {
        return true;
    }

    public @NotNull List<String> getPlaceholders() {
        return runtimes.placeholders();
    }

    public String onRequest(OfflinePlayer player, @NotNull String parameter) {
        return runtimes.placeholder(player instanceof Player ? player.getUniqueId() : null, parameter);
    }
}
