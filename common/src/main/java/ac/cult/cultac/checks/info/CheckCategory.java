package ac.cult.cultac.checks.info;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The group a moderator finds a check under; the label each shows is in {@code check-info/en.yml}. */
public enum CheckCategory {
    COMBAT,
    MOVEMENT,
    ELYTRA,
    VEHICLES,
    BUILDING,
    INVENTORY,
    CHAT,
    PACKETS,
    CRASH,
    CLOUD;

    /** The lower-case id typed in {@code /cult info checks <category>}. */
    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable CheckCategory byId(@NotNull String id) {
        for (CheckCategory category : values()) {
            if (category.id().equalsIgnoreCase(id)) return category;
        }
        return null;
    }
}
