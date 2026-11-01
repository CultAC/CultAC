package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;

public final class BlockSpeedFactors {
    private BlockSpeedFactors() {}

    public static float nextTickForResult(CultPlayer player, PredictionResult result) {
        PacketEntity vehicle = result.getSimulationContext().getVehicle();
        if (vehicle != null) {
            // Entity#move applies the root entity's own getBlockSpeedFactor, which no rider state reaches, on the
            // supporting block JavaMovementEngine#settleRiddenSupport finds from the root's own move.
            return CultMath.lerp(
                    (float) vehicle.movementEfficiency,
                    BlockProperties.getEntityBlockSpeedFactor(
                            player,
                            result.getSimulationContext().getWorldData().getMainSupportingBlockPos(),
                            result.getSimulationContext().getEnd()),
                    1.0F);
        }

        // MCP-Reborn Player#getBlockSpeedFactor returns 1.0F while the player is
        // fall-flying or ability-flying. Use the frozen SimulationContext for
        // fall-flying; mutable CultPlayer state may already describe a later packet.
        if (result.getSimulationContext().isGliding() || player.isFlying) {
            return 1.0f;
        }

        return CultMath.lerp(
                result.getSimulationContext().getMovementEfficiency(),
                BlockProperties.getBlockSpeedFactor(
                        player,
                        result.getSimulationContext().getWorldData().getMainSupportingBlockPos(),
                        result.getSimulationContext().getEnd()),
                1.0F);
    }
}
