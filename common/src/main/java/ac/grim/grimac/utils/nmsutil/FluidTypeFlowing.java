package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.util.Vector;

public class FluidTypeFlowing {
    public static Vector getFlow(GrimPlayer player, int originalX, int originalY, int originalZ) {
        BlockPos pos = new BlockPos(originalX, originalY, originalZ);
        FluidState fluidState = player.compensatedWorld.getFluidState(pos);
        if (fluidState.isEmpty()) return new Vector();

        Vec3 flow = fluidState.getFlow(player.compensatedWorld, pos);
        return new Vector(flow.x, flow.y, flow.z);
    }
}
