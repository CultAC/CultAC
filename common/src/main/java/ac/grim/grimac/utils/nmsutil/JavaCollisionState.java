package ac.grim.grimac.utils.nmsutil;

import ac.grim.grimac.checks.impl.prediction.PredictionCarry;
import ac.grim.grimac.checks.impl.prediction.pipeline.java.JavaPredictionCarry;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.bukkit.Material;

/** Immutable entity-dependent shape inputs; never backed by a live server entity. */
public record JavaCollisionState(double fallDistance, boolean walksOnPowderSnow,
                                 boolean descending, boolean fallingBlock) {
    private static final VoxelShape FALLING_SNOW = Shapes.box(0, 0, 0, 1, (double) 0.9F, 1);

    public static JavaCollisionState of(GrimPlayer player, PacketEntity actor, double fallDistance) {
        boolean self = actor == player.compensatedEntities.getSelf();
        boolean walks = BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(actor.type).is(EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS)
                || self && player.getInventory().getBoots().getType() == Material.LEATHER_BOOTS;
        return new JavaCollisionState(fallDistance, walks, self && player.isSneaking,
                actor.type == EntityTypesCompat.FALLING_BLOCK);
    }

    public static JavaCollisionState current(GrimPlayer player) {
        if (player == null || player.compensatedWorld == null || player.isBedrockMovement()) return null;
        PacketEntity actor = player.compensatedEntities.getEntityInControl();
        PredictionCarry carry = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit().carry();
        double distance = carry instanceof JavaPredictionCarry java && java.actor() == actor ? java.fallDistance() : 0.0;
        return of(player, actor, distance);
    }

    public VoxelShape powderSnowShape(int blockY, double entityBottom) {
        // Java PowderSnowBlock#getCollisionShape: falling precedes the boots/descending branch.
        // Preserve the promoted FLOAT height, not the double literal 0.9.
        if (fallDistance > 2.5) return FALLING_SNOW;
        if (fallingBlock || walksOnPowderSnow && !descending
                && entityBottom > blockY + 1.0 - (double) 1.0E-5F) return Shapes.block();
        return Shapes.empty();
    }
}
