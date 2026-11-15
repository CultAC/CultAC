package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.BlockProps;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.StateFacts;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaInsideBlockEffects;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vec3;

/** Older players' Entity#applyEffectsFromBlocks bubble callbacks, after travel, before next tick's inertia cut. */
public final class JavaBubbleColumnVelocity {
    private JavaBubbleColumnVelocity() {}

    public static Vec3 afterTravel(CultPlayer player, PredictionResult result, Vec3 velocity, boolean preciseGate) {
        SimulationContext context = result.getSimulationContext();
        if (!carries(player, context) || !VelocityCandidates.hasEngineTrace(result)) return velocity;
        Vec3 movement = result.hasEffectiveFlags()
                ? result.getLegacyLikePredictionVector().multiply(context.getLastStuckSpeed())
                : context.getEnd().subtract(context.getStart());
        return apply(player, context, JavaInsideBlockEffects.scan(player, result, movement), velocity, preciseGate);
    }

    /** 1.21.9 and 1.21.10 share protocol 773 but the latter adds BubbleColumnBlock's precision gate. */
    public static boolean hasPrecisionAlternatives(CultPlayer player, SimulationContext context) {
        return carries(player, context) && context.getVersion() == ClientVersion.V_1_21_9;
    }

    public static boolean carries(CultPlayer player, SimulationContext context) {
        return !player.isBedrockMovement()
                && context.getVehicle() == null
                && context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2);
    }

    public static Vec3 apply(
            CultPlayer player, SimulationContext context, JavaInsideBlockEffects.Scan scan, Vec3 velocity, boolean preciseGate) {
        if (scan == null || player.isFlying) return velocity;
        Vec3[] current = {velocity};
        Collisions.InsideBlockVisitor visitor = (from, to, pos, precise) -> {
            int block = player.compensatedWorld.getBlockStateIdAt(pos);
            if (!BlockIds.is(block, BlockIds.BUBBLE_COLUMN)) return false;
            if (!precise && preciseGate) return true;
            int above = player.compensatedWorld.getBlockStateIdAt(pos.above());
            boolean surface = context.getVersion().isOlderThan(ClientVersion.V_1_21_5)
                    ? DataTables.defaults().registry().facts(above).has(StateFacts.AIR)
                    : player.compensatedWorld.geometry().collision(above, pos).isEmpty()
                            && ClientFluidQueries.modelFluid(above).isEmpty();
            current[0] = JavaInsideBlockEffects.bubble(
                            new JavaInsideBlockEffects.State(0, current[0], null),
                            surface,
                            BlockProps.DRAG.booleanValue(block))
                    .velocity();
            return true;
        };
        if (context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_9)) {
            Collisions.visitInsideBlocks26Dot2(scan.from(), scan.to(), scan.originalMovement(), visitor);
        } else {
            Collisions.visitInsideBlocksBefore1Dot21Dot9(
                    scan.from(),
                    scan.to(),
                    context.getVersion().isNewerThanOrEquals(ClientVersion.V_1_21_5) ? scan.originalMovement() : null,
                    context.getVersion().isOlderThan(ClientVersion.V_1_21_4),
                    visitor);
        }
        return current[0];
    }
}
