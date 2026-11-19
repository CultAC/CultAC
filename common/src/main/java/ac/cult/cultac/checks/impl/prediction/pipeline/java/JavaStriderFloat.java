package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntityStrider;
import ac.cult.cultac.utils.math.Vec3;

/**
 * Strider#floatStrider on the controlled root. Strider#tick runs it after LivingEntity#tick, so after the travel's
 * Entity#move and block callbacks and before the 26.3 block-entity pass, and it reads the lava cache the travel left.
 * In lava, a strider above the 0.5-high liquid column of its block, with no lava over it, is set on the ground by
 * Entity#setOnGround(true), whatever its move did. Any other strider in lava is buoyed instead.
 */
final class JavaStriderFloat {
    private JavaStriderFloat() {}

    /** Whether floatStrider halves the delta movement and adds 0.05Y after {@code moved}. */
    static boolean buoys(CultPlayer player, SimulationContext context, JavaFallDistance.Moved moved) {
        return floats(context, moved.touchingLava())
                && !PacketEntityStrider.floatsOnLavaSurface(player, context.getStart().add(moved.movement()));
    }

    /** Whether floatStrider sets the strider on the ground after {@code moved}. */
    static boolean grounds(CultPlayer player, SimulationContext context, JavaFallDistance.Moved moved) {
        return grounds(player, context, moved.touchingLava(), context.getStart().add(moved.movement()));
    }

    /** Whether floatStrider sets the strider at {@code end} on the ground, with the lava cache {@code inLava}. */
    static boolean grounds(CultPlayer player, SimulationContext context, boolean inLava, Vec3 end) {
        return floats(context, inLava) && PacketEntityStrider.floatsOnLavaSurface(player, end);
    }

    /** {@link #grounds} for {@code result}'s travel, read before or after its commit. */
    static boolean grounds(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (!floats(context, true)) return false;
        JavaFallDistance.Moved moved = result.getJavaCommittedMove();
        if (moved == null) moved = JavaFallDistance.afterCollision(player, result, context.getPacketMovement());
        return grounds(player, context, moved);
    }

    /** Entity#setOnGround(true)'s supporting block for the strider at {@code end}, after the travel's support. */
    static MainSupportingBlockData support(
            CultPlayer player, SimulationContext context, Vec3 end, MainSupportingBlockData support) {
        return player.checkManager
                .getSimulationProcessor()
                .mainSupportingBlockAfterSetOnGround(support, context.getActualPoseAt(end), end);
    }

    private static boolean floats(SimulationContext context, boolean inLava) {
        return inLava && context.getVehicle() != null && context.getVehicle().isStrider();
    }
}
