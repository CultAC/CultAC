package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaMovementEngine;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.MainSupportingBlockData;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.math.CultMath;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;

public final class BlockSpeedFactors {
    private BlockSpeedFactors() {}

    public static float nextTickForResult(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        var pass = JavaMovementEngine.passAfterTravel(result);
        if (pass != null) {
            // Entity#move applies this before the later block-entity pass. A piston move may clear
            // the travel's supporting block or move onto a different block with its own speed factor.
            return atPosition(
                    player, context, pass.travel().support(), pass.travel().position());
        }
        return atPosition(player, context, context.getWorldData().getMainSupportingBlockPos(), context.getEnd());
    }

    /** Entity#getBlockSpeedFactor at one move's endpoint, with that move's supporting block. */
    public static float atPosition(
            CultPlayer player, SimulationContext context, MainSupportingBlockData support, Vec3 end) {
        return atPosition(player, context, support, end, context.isGliding());
    }

    /** A later Entity#move reads the flag left by travelFallFlying's stopFallFlying. */
    public static float afterTravelAtPosition(
            CultPlayer player, SimulationContext context, MainSupportingBlockData support, Vec3 end) {
        return atPosition(player, context, support, end, context.isGliding() && !context.stopsFallFlyingAfterTravel());
    }

    private static float atPosition(
            CultPlayer player, SimulationContext context, MainSupportingBlockData support, Vec3 end, boolean gliding) {
        PacketEntity vehicle = context.getVehicle();
        if (vehicle != null) {
            // Entity#move applies the root entity's own getBlockSpeedFactor, which no rider state reaches, on the
            // supporting block JavaMovementEngine#settleRiddenSupport finds from the root's own move.
            return entityAtPosition(player, vehicle, support, end);
        }

        // MCP-Reborn Player#getBlockSpeedFactor returns 1.0F while the player is
        // fall-flying or ability-flying. Use the frozen SimulationContext for
        // fall-flying; mutable CultPlayer state may already describe a later packet.
        if (gliding || player.isFlying) {
            return 1.0f;
        }

        return CultMath.lerp(
                context.getMovementEfficiency(), BlockProperties.getEntityBlockSpeedFactor(player, support, end), 1.0F);
    }

    /** The root's virtual getBlockSpeedFactor; player abilities do not belong to another entity's move. */
    public static float entityAtPosition(
            CultPlayer player, PacketEntity actor, MainSupportingBlockData support, Vec3 end) {
        if (actor.type == EntityTypeIds.PLAYER && (actor.actionSharedFlags & 128) != 0) return 1.0F;
        if (actor.isMinecart()) {
            int state = player.compensatedWorld.getBlockStateIdAt(
                    CultMath.floor(end.x), CultMath.floor(end.y), CultMath.floor(end.z));
            if (ClientFluidQueries.clientTags(player)
                    .blocks()
                    .getOrDefault("minecraft:rails", List.of())
                    .contains(DataTables.defaults().registry().block(state).key())) return 1.0F;
        }
        return CultMath.lerp(
                (float) actor.movementEfficiency,
                BlockProperties.getEntityBlockSpeedFactor(player, support, end),
                1.0F);
    }
}
