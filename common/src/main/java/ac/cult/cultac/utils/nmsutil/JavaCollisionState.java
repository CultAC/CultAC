package ac.cult.cultac.utils.nmsutil;

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
    /** States with a +0.0 fall distance by {@link #flagIndex}. */
    private static final JavaCollisionState[] AT_REST = atRest();

    public static JavaCollisionState of(CultPlayer player, PacketEntity actor, double fallDistance) {
        boolean self = actor == player.compensatedEntities.getSelf();
        boolean walks = EntityTags.POWDER_SNOW_WALKABLE_MOBS.test(actor.type)
                || self && player.getInventory().getBoots().getItem() == ItemTypes.LEATHER_BOOTS;
        return of(fallDistance, walks, isDescending(player, actor), actor.type == EntityTypeIds.FALLING_BLOCK);
    }

    /** A state with these components; a shared one when the fall distance is +0.0. */
    static JavaCollisionState of(
            double fallDistance, boolean walksOnPowderSnow, boolean descending, boolean fallingBlock) {
        if (Double.doubleToRawLongBits(fallDistance) == 0L)
            return AT_REST[flagIndex(walksOnPowderSnow, descending, fallingBlock)];
        return new JavaCollisionState(fallDistance, walksOnPowderSnow, descending, fallingBlock);
    }

    private static int flagIndex(boolean walksOnPowderSnow, boolean descending, boolean fallingBlock) {
        return (walksOnPowderSnow ? 1 : 0) | (descending ? 2 : 0) | (fallingBlock ? 4 : 0);
    }

    private static JavaCollisionState[] atRest() {
        JavaCollisionState[] states = new JavaCollisionState[8];
        for (int i = 0; i < states.length; i++)
            states[i] = new JavaCollisionState(0.0, (i & 1) != 0, (i & 2) != 0, (i & 4) != 0);
        return states;
    }

    /** Entity#isDescending: LocalPlayer's shift key, or another entity's shared crouching flag. */
    public static boolean isDescending(CultPlayer player, PacketEntity actor) {
        return actor == player.compensatedEntities.getSelf() ? player.isSneaking : (actor.actionSharedFlags & 2) != 0;
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
