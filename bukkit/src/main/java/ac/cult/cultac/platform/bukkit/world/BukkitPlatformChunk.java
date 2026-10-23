package ac.cult.cultac.platform.bukkit.world;

import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import net.minecraft.SharedConstants;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

public class BukkitPlatformChunk {
    private static final ModelBlockStates MODEL_STATES =
            ModelBlockStates.load(ProtocolVersion.of(SharedConstants.getProtocolVersion()), ProtocolVersion.V26_3);

    static int toModelState(int hostState) {
        return MODEL_STATES.toModel(hostState);
    }

    public static boolean isIllegalY(@NotNull World world, int y) {
        return world.getMinHeight() > y || y > world.getMaxHeight();
    }
}
