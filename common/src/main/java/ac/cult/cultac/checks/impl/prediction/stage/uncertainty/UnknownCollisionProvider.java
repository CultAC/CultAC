package ac.cult.cultac.checks.impl.prediction.stage.uncertainty;

import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityHappyGhast;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.nmsutil.EntityTypeUtil;
import ac.cult.cultac.utils.nmsutil.EntityTypesCompat;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class UnknownCollisionProvider {
    private UnknownCollisionProvider() {
    }

    public static List<SimpleCollisionBox> movementUnknownCollisions(
            CultPlayer player,
            SimulationContext context,
            Vec3 playerPos,
            SimpleCollisionBox queryBox
    ) {
        List<SimpleCollisionBox> boxes = entityUnknownCollisions(player, playerPos);
        addDynamicBlockUnknownCollisions(player, context, queryBox, boxes);
        return boxes;
    }

    public static List<SimpleCollisionBox> entityUnknownCollisions(CultPlayer player, Vec3 playerPos) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        boolean ridingStrider = player.compensatedEntities.getSelf().getRiding() instanceof PacketEntityStrider;
        boolean inBoat = player.compensatedEntities.getSelf().getRiding() != null
                && EntityTypeUtil.isBoat(player.compensatedEntities.getSelf().getRiding().type);
        boolean inVehicle = player.compensatedEntities.getSelf().inVehicle();

        for (PacketEntity entity : player.compensatedEntities.entityMap.values()) {
            if (EntityTypeUtil.isBoat(entity.type)) {
                // Expand by 2 since boats suck.
                boxes.add(entity.getPossibleCollisionBoxes().expand(2));
            }

            if (inBoat && !player.compensatedEntities.getSelf().getRiding().hasPassenger(entity)) {
                boolean canPush = entity.isLivingEntity();
                // Minecarts and boats are only non-living that can push
                if (EntityTypeUtil.isMinecart(entity.type) || EntityTypeUtil.isBoat(entity.type)) {
                    canPush = true;
                }
                // Bats, parrots, and armor stands cannot
                if (entity.type == EntityTypesCompat.BAT || entity.type == EntityTypesCompat.PARROT || entity.type == EntityTypesCompat.ARMOR_STAND) {
                    canPush = false;
                }
                // We ignore some edge cases like horses that are vehicles (why exempt this?) but it's fine.
                if (canPush) {
                    boxes.add(entity.getPossibleCollisionBoxes().expand(1));
                }
            }

            if (ridingStrider && entity.type == EntityTypesCompat.SHULKER && !player.compensatedEntities.getSelf().getRiding().hasPassenger(entity)) {
                // Striders suck less.
                boxes.add(entity.getPossibleCollisionBoxes().expand(1));
            }

            if (!inVehicle
                    && entity instanceof PacketEntityHappyGhast happyGhast
                    && !happyGhast.isBaby
                    && !happyGhast.isDead
                    && !happyGhast.hasPassenger(player.compensatedEntities.getSelf())) {
                SimpleCollisionBox box = happyGhast.getPossibleCollisionBoxes();
                // MCP-Reborn HappyGhast#canBeCollidedWith is client-only for a
                // Player whose position is at or above the ghast's top, and
                // otherwise follows the synced still-timeout state.
                if (happyGhast.staysStill || playerPos.y >= box.maxY - SimpleCollisionBox.COLLISION_EPSILON) {
                    boxes.add(box);
                }
            }
        }

        return boxes;
    }

    public static void addDynamicBlockUnknownCollisions(
            CultPlayer player,
            SimulationContext context,
            SimpleCollisionBox queryBox,
            List<SimpleCollisionBox> unknown
    ) {
        if (context.getVehicle() != null) {
            return;
        }

        unknown.addAll(player.compensatedWorld.getDynamicBlockCollisionUncertaintyBoxes(queryBox));
    }
}
