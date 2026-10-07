package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

/** Marker/small flags affect action geometry, without changing movement prediction. */
public final class PacketEntityArmorStand extends PacketEntity {
    private byte actionFlags;

    public PacketEntityArmorStand(CultPlayer player, int id, int type, Vec3 pos) {
        super(player, id, type, pos.x, pos.y, pos.z);
    }

    public boolean actionMarker() {
        return (actionFlags & 16) != 0;
    }

    public void updateActionMetadata(List<EntityMetadata.Entry> entries) {
        var flags = ac.cult.cultac.utils.nmsutil.WatchableIndexUtil.getIndex(entries, 15);
        if (flags != null && flags.value() instanceof Byte value) actionFlags = value;
    }

    public Box actionBounds(SimpleCollisionBox position) {
        var type = ac.cult.blocksim.entity.EntityTypes.defaults().byKey("minecraft:armor_stand");
        float factor = (actionFlags & 1) != 0 ? .5F : 1.0F;
        float width = type.width() * factor * scale, height = type.height() * factor * scale;
        if (actionMarker()) {
            width = 0;
            height = 0;
        }
        double x = position.minX + .5 * (position.maxX - position.minX);
        double z = position.minZ + .5 * (position.maxZ - position.minZ), radius = width / 2.0F;
        return new Box(x - radius, position.minY, z - radius, x + radius, position.minY + height, z + radius);
    }
}
