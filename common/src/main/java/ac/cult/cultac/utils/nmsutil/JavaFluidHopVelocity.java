package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.checks.impl.prediction.stage.MovementModifiers;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.math.Vec3;

/**
 * LivingEntity#jumpOutOfFluid after a horizontally colliding fluid travel: Entity#isFree of the moved box, after
 * fluid drag/gravity and before ridden floating and inside-block effects.
 */
final class JavaFluidHopVelocity {
    private JavaFluidHopVelocity() {}

    /** Whether isFree holds for {@code velocity}, using exact blocks, fluids and compensated entity boxes. */
    static DesyncStatus isFree(CultPlayer player, PredictionResult result, Vec3 velocity) {
        var context = result.getSimulationContext();
        var pass = JavaMovementEngine.passAfterTravel(result);
        Vec3 end = pass == null ? context.getEnd() : pass.travel().position();
        // Entity#move left the box EntityDimensions#makeBoundingBox builds at the travel's end, and isFree moves it.
        var probe = context.getActualPoseAt(end)
                .offset(velocity.x, velocity.y + (double) 0.6F - end.y + context.getStart().y, velocity.z);
        var actor = context.getVehicle() == null ? player.compensatedEntities.getSelf() : context.getVehicle();
        var moved = result.getJavaCommittedMove();
        if (moved == null) moved = JavaFallDistance.afterCollision(player, result, context.getPacketMovement());
        if (Collisions.getCollisionBoxes(
                player, probe, null, true, end.y, JavaCollisionState.of(player, actor, moved.fallDistance())))
            return DesyncStatus.FALSE;
        // LevelReader#containsAnyLiquid checks whole block cells, without comparing the fluid's surface height.
        // 26.3's findBlocksIn includes the cell containing the maximum corner, even on an integer face.
        boolean inclusive = context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_3);
        int maxX = inclusive ? (int) Math.floor(probe.maxX) + 1 : (int) Math.ceil(probe.maxX);
        int maxY = inclusive ? (int) Math.floor(probe.maxY) + 1 : (int) Math.ceil(probe.maxY);
        int maxZ = inclusive ? (int) Math.floor(probe.maxZ) + 1 : (int) Math.ceil(probe.maxZ);
        for (int x = (int) Math.floor(probe.minX); x < maxX; x++)
            for (int y = (int) Math.floor(probe.minY); y < maxY; y++)
                for (int z = (int) Math.floor(probe.minZ); z < maxZ; z++)
                    if (!ClientFluidQueries.fluidAt(player.compensatedWorld, new BlockPos(x, y, z))
                            .isEmpty()) return DesyncStatus.FALSE;
        // CollisionGetter#noEntityCollision, at the travel's end: an entity only touching the box still blocks it.
        return RootVehicleInterpolationCollision.noEntityCollision(player, actor, end, probe);
    }

    static Vec3 hop(Vec3 velocity) {
        return new Vec3(velocity.x, MovementModifiers.FLUID_HOP_Y, velocity.z);
    }
}
