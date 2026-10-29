package ac.cult.cultac.checks.info;

import java.util.Locale;
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

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable CheckCategory byId(String id) {
        for (CheckCategory category : values()) {
            if (category.id().equalsIgnoreCase(id)) return category;
        }
        return null;
    }
}
