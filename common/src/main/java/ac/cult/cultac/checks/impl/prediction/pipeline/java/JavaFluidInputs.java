package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;

/** LocalPlayer#goDownInWater before the aiStep cutoff, then LivingEntity#jumpInLiquid after it. */
public final class JavaFluidInputs {
    private JavaFluidInputs() {}

    private static boolean appliesTo(CultPlayer player, SimulationContext context) {
        return JavaTravelInputs.usesReportedKeys(player, context) && !player.isFlying;
    }

    public static PredVector beforeMovementThreshold(
            CultPlayer player, SimulationContext context, PredVector velocity) {
        if (appliesTo(player, context)
                && player.packetStateData.knownInput.shift()
                && context.getWorldData().isInWater())
            return velocity.add(0, -0.04F, 0, "LocalPlayer#goDownInWater");
        return velocity;
    }

    public static PredVector jumpInLiquid(CultPlayer player, SimulationContext context, PredVector velocity) {
        if (!appliesTo(player, context) || !player.packetStateData.knownInput.jump()) return velocity;
        // LivingEntity#aiStep clears jumping while isImmobile: health at most 0, or Player#isImmobile's sleeping.
        if (player.isInBed || !player.compensatedEntities.getSelf().actionAlive) return velocity;
        boolean water = context.getWorldData().isInWater();
        boolean lava = context.getWorldData().isInLava();
        if (!water && !lava) return velocity;
        // LivingEntity#aiStep selects lava's cached height first, even when both tags touch the body.
        double height = lava ? context.getWorldData().getLavaHeight() : context.getWorldData().getWaterHeight();
        double threshold = context.getPose().eyeHeight * context.getScale() < 0.4D ? 0.0D : 0.4D;
        boolean onGround = context.getLastOnGround().determineOptimistically();
        boolean waterJump = water && height > 0.0D && !(onGround && height <= threshold);
        boolean lavaJump = !waterJump && lava && !(onGround && height <= threshold);
        return waterJump || lavaJump ? velocity.add(0, 0.04F, 0, "LivingEntity#jumpInLiquid") : velocity;
    }
}
