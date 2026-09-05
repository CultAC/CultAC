package ac.cult.cultac.platform.api.manager;

import ac.cult.cultac.platform.api.player.PlatformPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface MessagePlaceHolderManager {
    @NotNull
    String replacePlaceholders(@Nullable PlatformPlayer player, @NotNull String string);
}
