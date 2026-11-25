package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Entity#getTeam of a client entity, which EntitySelector#pushableBy reads: the team of its scoreboard name, which
 * TamableAnimal#getTeam takes from a tamed animal's owner.
 */
public final class ClientEntityTeams {
    /** The local player, as the owner lookups find it. */
    private static final Object SELF = new Object();

    private ClientEntityTeams() {}

    public static @Nullable ClientTeamCollision.Team teamOf(CultPlayer player, PacketEntity entity) {
        ClientTeamCollision teams = player.compensatedEntities.teamCollision;
        ClientTeamCollision.Team own = teams.teamOf(entity.scoreboardName);
        if (entity.tamable == null || !entity.tamable.tame()) return own;
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5)) {
            // 1.21.2-1.21.4: a tame animal whose owner the level holds as a player takes that player's team first;
            // OwnableEntity#getOwner looks the owner up with EntityGetter#getPlayerByUUID.
            Object owner = player(player, entity.tamable.owner());
            return owner == null ? own : teams.teamOf(scoreboardName(player, owner));
        }
        // 1.21.5+: the animal's own team first, then its root owner's.
        if (own != null) return own;
        Object owner = rootOwner(player, entity);
        return owner == null ? null : teams.teamOf(scoreboardName(player, owner));
    }

    /**
     * OwnableEntity#getRootOwner: the owner EntityReference#getEntity resolves, followed while it is itself an
     * OwnableEntity. An AbstractHorse's or Vex's owner is not synced, so the client resolves none for it.
     */
    private static @Nullable Object rootOwner(CultPlayer player, PacketEntity entity) {
        List<Object> seen = new ArrayList<>(2);
        seen.add(entity);
        Object owner = living(player, entity.tamable.owner());
        while (owner instanceof PacketEntity ownable && isOwnable(ownable)) {
            Object next = ownable.tamable == null ? null : living(player, ownable.tamable.owner());
            if (next != null && seen.contains(next)) return null;
            seen.add(ownable);
            owner = next;
        }
        return owner;
    }

    private static boolean isOwnable(PacketEntity entity) {
        return entity.tamable != null || EntityTypeUtil.isHorseFamily(entity.type) || entity.type == EntityTypeIds.VEX;
    }

    /** Level#getEntityInAnyDimension: the client's LivingEntity with {@code uuid}, or null. */
    private static @Nullable Object living(CultPlayer player, @Nullable UUID uuid) {
        if (uuid == null) return null;
        if (uuid.equals(player.getUniqueId())) return SELF;
        String name = uuid.toString();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (entity instanceof PacketEntityPlayer remote) {
                if (remote.actionPresent && uuid.equals(remote.profile)) return remote;
            } else if (entity.isLivingEntity() && name.equals(entity.scoreboardName)) return entity;
        }
        return null;
    }

    /** EntityGetter#getPlayerByUUID: the client's player with {@code uuid}, or null. */
    private static @Nullable Object player(CultPlayer player, @Nullable UUID uuid) {
        if (uuid == null) return null;
        if (uuid.equals(player.getUniqueId())) return SELF;
        for (PacketEntity entity : player.compensatedEntities.entityMap.values())
            if (entity instanceof PacketEntityPlayer remote && remote.actionPresent && uuid.equals(remote.profile))
                return remote;
        return null;
    }

    /** Entity#getScoreboardName: a player's profile name, else the UUID. */
    private static @Nullable String scoreboardName(CultPlayer player, Object owner) {
        return owner == SELF ? player.getName() : ((PacketEntity) owner).scoreboardName;
    }
}
