package ac.cult.cultac.utils.data.packetentity;

import ac.cult.blocksim.data.Box;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.ClientPlayerModes;
import ac.cult.cultac.utils.math.Vec3;
import java.util.UUID;

/** Remote-player facts needed by action queries; packet movement remains in PacketEntity. */
public final class PacketEntityPlayer extends PacketEntity {
    public UUID profile;
    public boolean actionPresent = true;
    public ClientPlayerModes.Entry cachedInfo;

    public PacketEntityPlayer(CultPlayer player, int id, int type, Vec3 pos) {
        super(player, id, type, pos.x, pos.y, pos.z);
    }

    public void cacheInfo(ClientPlayerModes profiles) {
        if (cachedInfo == null && profile != null) cachedInfo = profiles.get(profile);
    }

    public boolean actionSpectator(ClientPlayerModes profiles) {
        if (profile == null)
            throw new IllegalStateException("Remote player action query requires its received profile UUID");
        cacheInfo(profiles);
        return cachedInfo != null && cachedInfo.mode() == GameMode.SPECTATOR;
    }

    public Box actionBounds(SimpleCollisionBox position) {
        float width = avatarWidth(actionPose, scale), height = avatarHeight(actionPose, scale);
        double x = position.minX + .5 * (position.maxX - position.minX);
        double z = position.minZ + .5 * (position.maxZ - position.minZ), radius = width / 2.0F;
        return new Box(x - radius, position.minY, z - radius, x + radius, position.minY + height, z + radius);
    }
}
