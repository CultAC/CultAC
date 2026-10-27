package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.Box;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.blockplace.ClientEntityQueries;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The entities EntityGetter#getEntityCollisions gives a moving Java {@code actor} at {@code actorPosition}, through
 * its Entity#canCollideWith. The default asks the other entity's Entity#canBeCollidedWith: a boat, a live shulker, or a
 * happy ghast HappyGhast#canBeCollidedWith admits. A moving boat or minecart uses AbstractBoat#canVehicleCollide, which
 * also admits an Entity#isPushable entity. Neither admits an entity of the actor's own root vehicle tree
 * (Entity#isPassengerOfSameVehicle). Each answer is for one position the client may hold the entity at.
 */
public final class EntityCollisionSelection {
    private final CultPlayer player;
    private final PacketEntity actor;
    private final Vec3 actorPosition;
    private final PacketEntity actorRoot;
    private final boolean pushables;
    private ClientEntityQueries queries;

    public EntityCollisionSelection(CultPlayer player, PacketEntity actor, Vec3 actorPosition) {
        this.player = player;
        this.actor = actor;
        this.actorPosition = actorPosition;
        this.actorRoot = player.compensatedEntities.rootOf(actor);
        this.pushables = actor.isBoat() || actor.isMinecart();
    }

    /** Whether AbstractBoat#canVehicleCollide reads Entity#isPushable: the actor is a boat or minecart. */
    public boolean includesPushables() {
        return pushables;
    }

    /** Whether Entity#canCollideWith can admit {@code entity}, other than the local player, at some position. */
    public boolean mayCollide(PacketEntity entity) {
        if (entity == null || entity == actor || entity.isDead || sharesRootVehicle(entity)) return false;
        if (entity.isBoat() || entity instanceof PacketEntityShulker shulker && shulker.collidable()) return true;
        if (entity instanceof PacketEntityHappyGhast) return true;
        return pushables && (entity.isMinecart() || entity.isLivingEntity());
    }

    /** Whether Entity#canCollideWith admits the local player: only a moving boat or minecart pushes against it. */
    public boolean mayCollideWithLocalPlayer() {
        PacketEntity self = player.compensatedEntities.getSelf();
        return pushables && self != actor && !sharesRootVehicle(self);
    }

    /** The Java bounding box of {@code entity} at {@code candidate}, one of its possible position boxes. */
    public SimpleCollisionBox collisionBox(PacketEntity entity, SimpleCollisionBox candidate) {
        return entity.toBoundingBox(player, candidate.copy());
    }

    /**
     * Whether Entity#canCollideWith admits {@code entity}, which {@link #mayCollide} admits, while it holds
     * {@code box}, its {@link #collisionBox}.
     */
    public boolean collidesAt(PacketEntity entity, SimpleCollisionBox box) {
        if (entity.isBoat() || entity instanceof PacketEntityShulker shulker && shulker.collidable()) return true;
        if (entity instanceof PacketEntityHappyGhast ghast && ghast.canBeCollidedWith(actor, actorPosition, box))
            return true;
        if (!pushables) return false;
        if (queries == null)
            queries = new ClientEntityQueries(
                    player, player.compensatedWorld.geometry().blockView());
        return queries.pushableBy(entity, new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
    }

    private boolean sharesRootVehicle(PacketEntity entity) {
        PacketEntity root = player.compensatedEntities.rootOf(entity);
        return root != null && root == actorRoot;
    }
}
