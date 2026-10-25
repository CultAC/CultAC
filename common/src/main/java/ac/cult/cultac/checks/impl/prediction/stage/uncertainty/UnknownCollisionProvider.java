package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityShulker;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.jetbrains.annotations.Nullable;

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
        addEntityUnknownCollisions(player, context.getVehicle(), playerPos, queryBox, boxes, entityBox, scratch);
        addDynamicBlockUnknownCollisions(player, context, queryBox, boxes);
    }

    /**
     * Adds a copy of each entity box that intersects {@code queryBox} for the move of {@code vehicle}, or of the player
     * outside a vehicle when it is null. {@code entityBox} and {@code scratch} are working space owned by the caller.
     */
    private static void addEntityUnknownCollisions(
            CultPlayer player,
            @Nullable PacketEntity vehicle,
            Vec3 playerPos,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> boxes,
            SimpleCollisionBox entityBox,
            SimpleCollisionBox scratch) {
        boolean inBoat = player.compensatedEntities.getSelf().getRiding() != null
                && player.compensatedEntities.getSelf().getRiding().isBoat();
        boolean inVehicle = player.compensatedEntities.getSelf().inVehicle();

        Iterable<PacketEntity> candidates = inBoat
                ? player.compensatedEntities.entityMap.values()
                : player.compensatedEntities.unknownCollisionCandidates();
        PacketEntity selfRidingRoot = player.compensatedEntities.selfRidingRoot();
        PacketEntity serverVehicleRoot = player.compensatedEntities.serverVehicleRoot();
        PacketEntity rootOfSelf = inVehicle ? player.compensatedEntities.rootOfSelf() : null;
        for (PacketEntity entity : candidates) {
            if (player.compensatedEntities.isUntrackedRider(entity, selfRidingRoot, serverVehicleRoot)) continue;
            // EntityGetter#getEntityCollisions never returns the moving vehicle itself, and
            // AbstractBoat#canVehicleCollide rejects anything Entity#isPassengerOfSameVehicle.
            if (inVehicle && player.compensatedEntities.sharesRootVehicleWithSelf(entity, rootOfSelf)) continue;
            if (entity.isBoat()) {
                // Expand by 2 since boats suck.
                addIntersecting(
                        boxes,
                        entity.getPossibleCollisionBoxes(entityBox, scratch).expand(2),
                        queryBox);
            }

            if (inBoat && !player.compensatedEntities.getSelf().getRiding().hasPassenger(entity)) {
                boolean canPush = entity.isLivingEntity();
                // Minecarts and boats are only non-living that can push
                if (entity.isMinecart() || entity.isBoat()) {
                    canPush = true;
                }
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

            if (entity instanceof PacketEntityHappyGhast happyGhast
                    && !happyGhast.hasPassenger(player.compensatedEntities.getSelf())) {
                SimpleCollisionBox box = happyGhast.getPossibleMovementCollisionBoxes(entityBox, scratch);
                if (canBeCollidedWith(happyGhast, box, vehicle, playerPos)) addIntersecting(boxes, box, queryBox);
            }

            // Java Shulker#canBeCollidedWith admits every living shulker.
            if (!inBoat
                    && !player.isBedrockMovement()
                    && entity instanceof PacketEntityShulker shulker
                    && !shulker.isDead) {
                addIntersecting(
                        boxes,
                        shulker.boundingBox(shulker.getPossibleMovementCollisionBoxes(entityBox, scratch)),
                        queryBox);
            }
        }
    }

    /**
     * Whether {@code entity} collides with the moving {@code vehicle}, or with the player outside a vehicle when it is
     * null, at each of its possible positions: a boat, a living shulker, or a happy ghast not carrying {@code self}
     * that HappyGhast#canBeCollidedWith admits. {@code envelope} and {@code scratch} are working space owned by the
     * caller.
     */
    public static boolean collidesAtPossiblePositions(
            PacketEntity self,
            @Nullable PacketEntity vehicle,
            Vec3 playerPos,
            PacketEntity entity,
            SimpleCollisionBox envelope,
            SimpleCollisionBox scratch) {
        if (entity.isBoat()) return true;
        if (entity instanceof PacketEntityShulker) return !entity.isDead;
        return entity instanceof PacketEntityHappyGhast happyGhast
                && !happyGhast.hasPassenger(self)
                && canBeCollidedWith(
                        happyGhast,
                        happyGhast.getPossibleMovementCollisionBoxes(envelope, scratch),
                        vehicle,
                        playerPos);
    }

    /**
     * HappyGhast#canBeCollidedWith for the moving {@code vehicle}, or for the player at {@code playerPos} outside a
     * vehicle when it is null, with {@code box} the ghast's possible movement box. On the client the still timeout is
     * the synced STAYS_STILL; a player at or above the top of the box also collides, and a ridden happy ghast collides
     * with another ridden one.
     */
    public static boolean canBeCollidedWith(
            PacketEntityHappyGhast happyGhast, SimpleCollisionBox box, @Nullable PacketEntity vehicle, Vec3 playerPos) {
        if (happyGhast.isBaby || happyGhast.isDead) return false;
        if (happyGhast.staysStill) return true;
        if (vehicle == null) return playerPos.y >= box.maxY - SimpleCollisionBox.COLLISION_EPSILON;
        return vehicle instanceof PacketEntityHappyGhast && !happyGhast.passengers.isEmpty();
    }

    private static void addIntersecting(
            List<SimpleCollisionBox> boxes, SimpleCollisionBox box, SimpleCollisionBox queryBox) {
        if (box.isIntersected(queryBox)) boxes.add(box.copy());
    }

    public static void addDynamicBlockUnknownCollisions(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> unknown) {
        if (context.getVehicle() != null) {
            return;
        }

        player.compensatedWorld.addDynamicBlockCollisionUncertaintyBoxes(queryBox, unknown);
    }
}
