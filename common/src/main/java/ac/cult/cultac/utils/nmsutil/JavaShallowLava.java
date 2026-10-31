package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.data.FluidTags;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;

/**
 * LivingEntity#isInShallowFluid(LAVA), which LivingEntity#travelInLava reads after its Entity#move to choose its drag:
 * the cached getFluidHeight(LAVA) at or below Entity#getFluidJumpThreshold().
 */
final class JavaShallowLava {
    private JavaShallowLava() {}

    static boolean test(CultPlayer player, PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        PacketEntity vehicle = context.getVehicle();
        PacketEntity actor = vehicle == null ? player.compensatedEntities.getSelf() : vehicle;
        // Entity#baseTick caches the height at the travel's start. From 26.1 LivingEntity#checkFallDamage refreshes
        // every fluid inside the travel's Entity#move; through 1.21.11 it refreshes water only.
        double height = context.getVersion().isNewerThanOrEquals(ClientVersion.V_26_1)
                        && JavaLivingMoveFluids.refreshes(context.getVersion(), actor, false, true)
                ? ClientFluidQueries.depth(player, VelocityCandidates.postMoveFluidBox(result), FluidTags.LAVA)
                : context.getWorldData().getLavaHeight();
        return height <= ClientFluidQueries.fluidJumpThreshold(context);
    }
}
