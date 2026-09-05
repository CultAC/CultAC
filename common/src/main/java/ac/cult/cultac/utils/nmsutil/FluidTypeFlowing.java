package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;

public class FluidTypeFlowing {
    public static Vector getFlow(CultPlayer player, int originalX, int originalY, int originalZ) {
        BlockPos pos = new BlockPos(originalX, originalY, originalZ);
        FluidState fluidState = player.compensatedWorld.getFluidState(pos);
        if (fluidState.isEmpty()) return new Vector();

        Vec3 flow = fluidState.getFlow(player.compensatedWorld, pos);
        return new Vector(flow.x, flow.y, flow.z);
    }
}
