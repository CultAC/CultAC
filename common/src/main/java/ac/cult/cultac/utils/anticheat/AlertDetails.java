package ac.cult.cultac.utils.anticheat;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.CultAPI;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.latency.CompensatedInventory;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Where a player is and what the client holds, as alert placeholders and the webhook's state line. */
public final class AlertDetails {
    private static final String UNKNOWN = "unknown";

    private AlertDetails() {}

    /** The backend the player is on behind a proxy, otherwise this server's configured datastore name. */
    public static @NotNull String server(@NotNull CultPlayer player) {
        var platform = player.user.getCultConnection().platform();
        String backend = platform == null ? null : platform.backendName();
        if (backend != null) return backend;
        return CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse("database.server-name", UNKNOWN);
    }

    /** The level the client was last sent into, as the server keys it. */
    public static @NotNull String world(@NotNull CultPlayer player) {
        String world = player.world;
        return world == null ? UNKNOWN : withoutDefaultNamespace(world);
    }

    /** The dimension type of that level: overworld, the_nether, the_end or a custom type. */
    public static @NotNull String worldType(@NotNull CultPlayer player) {
        String dimension = player.dimension;
        return dimension == null ? UNKNOWN : withoutDefaultNamespace(dimension);
    }

    public static @NotNull String gamemode(@NotNull CultPlayer player) {
        return player.gamemode == null ? UNKNOWN : player.gamemode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The compensated client state at the alert, one {@code key=value} per entry. {@code sprint} is the client's last
     * START/STOP_SPRINTING; {@code sprint_flag} and {@code sprint_modifier} are the two sprint states the client holds
     * from the server (see {@link ac.cult.cultac.utils.data.ClientSprintState}).
     */
    public static @NotNull String state(@NotNull CultPlayer player) {
        StringBuilder state = new StringBuilder(256);
        append(state, "gamemode", gamemode(player));
        append(state, "sprint", player.isSprinting);
        append(state, "sprint_flag", player.sprintState.flag());
        append(state, "sprint_modifier", player.sprintState.modifier());
        append(state, "sneak", player.isSneaking);
        append(state, "swim", player.swimState.swimming());
        append(state, "glide", player.isGliding);
        append(state, "fly", player.isFlying);
        append(state, "on_ground", player.onGround);
        append(state, "vehicle", player.inVehicle());
        CompensatedInventory inventory = player.getInventory();
        append(state, "holding", inventory == null ? UNKNOWN : item(inventory.getHeldItem()));
        append(state, "offhand", inventory == null ? UNKNOWN : item(inventory.getOffHand()));
        append(state, "inventory_open", player.hasInventoryOpen);
        append(state, "transaction_ping", player.getTransactionPing());
        append(state, "keepalive_ping", player.getKeepAlivePing());
        return state.toString();
    }

    private static void append(StringBuilder state, String key, Object value) {
        if (!state.isEmpty()) state.append(", ");
        state.append(key).append('=').append(value);
    }

    private static String item(@Nullable SimItemStack item) {
        if (item == null || item.isEmpty()) return "air";
        String key = withoutDefaultNamespace(item.itemKey());
        return item.getCount() > 1 ? key + " x" + item.getCount() : key;
    }

    private static String withoutDefaultNamespace(String key) {
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }
}
