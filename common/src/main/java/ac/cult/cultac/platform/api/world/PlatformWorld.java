package ac.cult.cultac.platform.api.world;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

public interface PlatformWorld {
    boolean isChunkLoaded(int chunkX, int chunkZ);

    /** Returns a state in the generated 26.3 model ID space. */
    int getBlockAt(int x, int y, int z);

    String getName();

    @Nullable
    UUID getUID();

    PlatformChunk getChunkAt(int currChunkX, int currChunkZ);

    boolean isLoaded();
}
