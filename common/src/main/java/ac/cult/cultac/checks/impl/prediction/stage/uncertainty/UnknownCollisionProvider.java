package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.math.Vec3;
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
        addEntityUnknownCollisions(player, playerPos, queryBox, boxes, entityBox, scratch);
        addDynamicBlockUnknownCollisions(player, context, queryBox, boxes);
    }

    /**
     * Adds a copy of each entity box that intersects {@code queryBox}. {@code entityBox} and {@code scratch} are
     * working space owned by the caller.
     */
    private static void addEntityUnknownCollisions(
            CultPlayer player,
            Vec3 playerPos,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> boxes,
            SimpleCollisionBox entityBox,
            SimpleCollisionBox scratch) {
        boolean ridingStrider = player.compensatedEntities.getSelf().getRiding() instanceof PacketEntityStrider;
        boolean inBoat = player.compensatedEntities.getSelf().getRiding() != null
                && player.compensatedEntities.getSelf().getRiding().isBoat();
        boolean inVehicle = player.compensatedEntities.getSelf().inVehicle();

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (player.compensatedEntities.isUntrackedRider(entity)) continue;
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
                            entity.getPossibleCollisionBoxes(entityBox, scratch).expand(1),
                            queryBox);
                }
            }

            if (ridingStrider
                    && entity.type == EntityTypeIds.SHULKER
                    && !player.compensatedEntities.getSelf().getRiding().hasPassenger(entity)) {
                // Striders suck less.
                addIntersecting(
                        boxes,
                        entity.getPossibleCollisionBoxes(entityBox, scratch).expand(1),
                        queryBox);
            }

            if (!inVehicle
                    && entity instanceof PacketEntityHappyGhast happyGhast
                    && !happyGhast.isBaby
                    && !happyGhast.isDead
                    && !happyGhast.hasPassenger(player.compensatedEntities.getSelf())) {
                SimpleCollisionBox box = happyGhast.getPossibleMovementCollisionBoxes(entityBox, scratch);
                // MCP-Reborn HappyGhast#canBeCollidedWith is client-only for a
                // Player whose position is at or above the top of the ghast's
                // concrete bounding box, and otherwise follows the synced
                // still-timeout state.
                if (happyGhast.staysStill || playerPos.y >= box.maxY - SimpleCollisionBox.COLLISION_EPSILON) {
                    addIntersecting(boxes, box, queryBox);
                }
            }
        }
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
