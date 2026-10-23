package ac.cult.cultac.manager;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reads the alert subscriber sets directly; the public queries overload on a server type absent from tests. */
public final class AlertSets {
    private AlertSets() {}

    public static List<String> subscribed(PlatformPlayer player) throws ReflectiveOperationException {
        Class<?> types = Class.forName(AlertManagerImpl.class.getName() + "$AlertType");
        var players = types.getField("players");
        players.setAccessible(true);
        List<String> subscribed = new ArrayList<>();
        for (Object type : types.getEnumConstants()) {
            if (((Set<?>) players.get(type)).contains(player)) subscribed.add(((Enum<?>) type).name());
        }
        return subscribed;
    }
}
