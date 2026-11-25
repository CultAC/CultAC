package ac.cult.cultac.platform.api.world;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface PlatformWorld {
    String getName();

    @Nullable
    UUID getUID();

    boolean isLoaded();
}
