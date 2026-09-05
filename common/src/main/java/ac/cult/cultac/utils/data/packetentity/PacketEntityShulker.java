package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;
import org.bukkit.block.BlockFace;
import net.minecraft.world.entity.EntityType;

public class PacketEntityShulker extends PacketEntity {
    public BlockFace facing = BlockFace.DOWN;

    public PacketEntityShulker(CultPlayer player, int entityId, EntityType type, double x, double y, double z) { super(player, entityId, type, x, y, z); }
}
