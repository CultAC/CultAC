package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.EntityCollisionSelection;
import java.util.List;

public final class UnknownCollisionProvider {
    private UnknownCollisionProvider() {}

    public static void addMovementUnknownCollisions(
            CultPlayer player,
            SimulationContext context,
            Vec3 playerPos,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> boxes,
            SimpleCollisionBox entityBox,
            SimpleCollisionBox scratch) {
        if (player.isBedrockMovement()) {
            addBedrockEntityUnknownCollisions(player, queryBox, boxes, entityBox, scratch);
        } else {
            PacketEntity actor =
                    context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
            boxes.addAll(EntityPositionWorlds.colliders(player, actor, playerPos, queryBox));
        }
    }

    /**
     * Adds a copy of each entity box that may push the boat a Bedrock player rides and intersects {@code queryBox}.
     * Solid actors are exact shapes of a Bedrock move instead, and a boat takes none of them. {@code entityBox} and
     * {@code scratch} are working space owned by the caller.
     */
    private static void addBedrockEntityUnknownCollisions(
            CultPlayer player,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> boxes,
            SimpleCollisionBox entityBox,
            SimpleCollisionBox scratch) {
        PacketEntity riding = player.compensatedEntities.getSelf().getRiding();
        if (riding == null || !riding.isBoat()) return;

        PacketEntity selfRidingRoot = player.compensatedEntities.selfRidingRoot();
        PacketEntity serverVehicleRoot = player.compensatedEntities.serverVehicleRoot();
        PacketEntity rootOfSelf = player.compensatedEntities.rootOfSelf();
        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (player.compensatedEntities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) continue;
            // EntityGetter#getEntityCollisions never returns the moving vehicle itself, and
            // AbstractBoat#canVehicleCollide rejects anything Entity#isPassengerOfSameVehicle.
            if (player.compensatedEntities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            if (riding.hasPassenger(entity)) continue;
            // Minecarts are the only non-living that can push
            boolean canPush = entity.isLivingEntity() || entity.isMinecart();
            // Bats, parrots, and armor stands cannot
            if (entity.type == EntityTypeIds.BAT
                    || entity.type == EntityTypeIds.PARROT
                    || entity.type == EntityTypeIds.ARMOR_STAND) {
                canPush = false;
            }
            // We ignore some edge cases like horses that are vehicles (why exempt this?) but it's fine.
            if (canPush) {
                addIntersecting(
                        boxes,
                        entity.toBoundingBox(player, entity.getPossibleCollisionBoxes(entityBox, scratch))
                                .expand(1),
                        queryBox);
            }
        }
    }

    /**
     * Whether EntityGetter#getEntityCollisions gives a Java player outside a vehicle, at {@code playerPos}, a shape for
     * {@code box}, which CollisionGetter#noCollision counts with the blocks: a box of a boat, live shulker or happy
     * ghast it collides with meets {@code box} inflated by 1.0E-7, at its compensated client-tick position, a rider's
     * at its seat.
     */
    public static boolean hasEntityCollision(CultPlayer player, Vec3 playerPos, SimpleCollisionBox box) {
        if (player.isBedrockMovement() || player.compensatedEntities.getSelf().inVehicle()) return false;
        // EntityGetter#getEntityCollisions skips a box whose average side is below 1.0E-7.
        if (((box.maxX - box.minX) + (box.maxY - box.minY) + (box.maxZ - box.minZ)) / 3.0 < 1.0E-7) return false;
        CompensatedEntities entities = player.compensatedEntities;
        List<PacketEntity> candidates = entities.unknownCollisionCandidates();
        if (candidates.isEmpty()) return false;
        PacketEntity selfRidingRoot = entities.selfRidingRoot();
        PacketEntity serverVehicleRoot = entities.serverVehicleRoot();
        SimpleCollisionBox query = box.copy().expand(1.0E-7);
        SimpleCollisionBox envelope = new SimpleCollisionBox();
        SimpleCollisionBox scratch = new SimpleCollisionBox();
        var selection = new EntityCollisionSelection(player, entities.getSelf(), playerPos);
        for (int e = 0, size = candidates.size(); e < size; e++) {
            PacketEntity entity = candidates.get(e);
            if (!selection.mayCollide(entity)) continue;
            boolean rider = entities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot);
            if (EntityPositionWorlds.collider(player, selection, entity, query, rider, envelope, scratch) != null)
                return true;
        }
        return false;
    }

    private static void addIntersecting(
            List<SimpleCollisionBox> boxes, SimpleCollisionBox box, SimpleCollisionBox queryBox) {
        if (box.isIntersected(queryBox)) boxes.add(box.copy());
    }
}
