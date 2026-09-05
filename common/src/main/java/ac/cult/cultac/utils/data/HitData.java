package ac.cult.cultac.utils.data;

import net.minecraft.core.BlockPos;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import net.minecraft.world.phys.Vec3;
import lombok.Getter;
import lombok.ToString;
import org.bukkit.util.Vector;

@Getter
@ToString
public class HitData {
    BlockPos position;
    Vector blockHitLocation;
    BlockData state;
    BlockFace closestDirection;

    public HitData(BlockPos position, Vector blockHitLocation, BlockFace closestDirection, BlockData state) {
        this.position = position;
        this.blockHitLocation = blockHitLocation;
        this.closestDirection = closestDirection;
        this.state = state;
    }

    public Vec3 getRelativeBlockHitLocation() {
        return new Vec3(blockHitLocation.getX() - position.getX(), blockHitLocation.getY() - position.getY(), blockHitLocation.getZ() - position.getZ());
    }
}
