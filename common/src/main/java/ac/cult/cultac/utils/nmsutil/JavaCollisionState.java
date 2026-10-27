package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.engine.shapes.Shapes;
import ac.cult.blocksim.engine.shapes.VoxelShape;
import ac.cult.blocksim.entity.EntityTags;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionCarry;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.inventory.ItemTypes;

/** Immutable entity-dependent shape inputs; never backed by a live server entity. */
public record JavaCollisionState(
        double fallDistance, boolean walksOnPowderSnow, boolean descending, boolean fallingBlock) {
    private static final VoxelShape FALLING_SNOW = Shapes.create(0, 0, 0, 1, (double) 0.9F, 1);

    public static JavaCollisionState of(CultPlayer player, PacketEntity actor, double fallDistance) {
        boolean self = actor == player.compensatedEntities.getSelf();
        boolean walks = EntityTags.POWDER_SNOW_WALKABLE_MOBS.test(actor.type)
                || self && player.getInventory().getBoots().getItem() == ItemTypes.LEATHER_BOOTS;
        // Entity#isDescending is isShiftKeyDown: LocalPlayer's shift key, or another entity's shared flag 1.
        boolean descending = self ? player.isSneaking : (actor.actionSharedFlags & 2) != 0;
        return new JavaCollisionState(fallDistance, walks, descending, actor.type == EntityTypeIds.FALLING_BLOCK);
    }

    /** Whether the state's collision shape reads these moving-entity inputs rather than only its bottom. */
    public static boolean readsActor(int state) {
        return BlockIds.is(state, BlockIds.POWDER_SNOW) || BlockIds.is(state, BlockIds.SCAFFOLDING);
    }

    public static JavaCollisionState current(CultPlayer player) {
        if (player == null || player.compensatedWorld == null || player.isBedrockMovement()) return null;
        PacketEntity actor = player.compensatedEntities.getEntityInControl();
        PredictionCarry carry = player.checkManager.getSimulationProcessor().getCurrentPredictionCarry();
        double distance =
                carry instanceof JavaPredictionCarry java && java.actor() == actor ? java.fallDistance() : 0.0;
        return of(player, actor, distance);
    }

    public VoxelShape powderSnowShape(int blockY, double entityBottom) {
        // Java PowderSnowBlock#getCollisionShape: falling precedes the boots/descending branch.
        // Preserve the promoted FLOAT height, not the double literal 0.9.
        if (fallDistance > 2.5) return FALLING_SNOW;
        if (fallingBlock || walksOnPowderSnow && !descending && entityBottom > blockY + 1.0 - (double) 1.0E-5F)
            return Shapes.block();
        return Shapes.empty();
    }
}
