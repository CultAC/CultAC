package ac.cult.cultac.checks.impl.prediction.pipeline.java;

import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.ClientBlockEntityPass;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.VelocityCandidates;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Physical travel on a CLIENT_TICK_END that reports no position: the local player's while another entity is the camera
 * (LocalPlayer#sendPosition reports nothing then, and LocalPlayer#applyInput falls back to LivingEntity#applyInput,
 * which only decays xxa/zza and keeps jumping), or the root it controls while LocalPlayer#sendChanges waits for the
 * client to load.
 */
public final class JavaUnreportedTravel {
    private JavaUnreportedTravel() {}

    /** Where the travel and the block-entity pass after it left the actor, and Entity#move's onGround there. */
    public record End(Vec3 position, boolean onGround) {}

    /**
     * The travel of {@code vehicle}, or of the player where it is null, while the player holds neither xxa/zza nor
     * jumping, or null where the engine's candidates disagree on it or it depends on what the client no longer reports:
     * Player#travel's swim pull and fall flying read the look, and a root's report follows its interpolation target.
     * Each candidate's input-free travel is replayed from before JavaForwardMove chose an input toward the probe's
     * target; jumpFromGround needs LivingEntity#jumping. LocalPlayer#goDownInWater still reads the held shift, which
     * this travel does not model.
     */
    public static End unique(CultPlayer player, PredictionResult prediction, @Nullable PacketEntity vehicle) {
        if (player.isBedrockMovement() || !VelocityCandidates.hasEngineTrace(prediction)) return null;
        var realities = new ArrayList<PredictionResult>();
        realities.add(prediction);
        for (var reality : prediction.getRealities()) if (reality != prediction) realities.add(reality);
        End selected = null;
        for (var reality : realities) {
            if (!VelocityCandidates.hasEngineTrace(reality)
                    || reality.getInitialStartingVel().isJump()) continue;
            SimulationContext context = reality.getSimulationContext();
            if (context.getVehicle() != vehicle) return null;
            if (vehicle != null
                    ? JavaVehicleReport.followsTarget(player, context)
                    : context.getWorldData().isSwimming()
                            || context.usesFallFlyingMovement()
                            || context.getWorldData().isInWater() && player.packetStateData.knownInput.shift())
                return null;
            var travel = reality.getValidMovements().getBeforeForwardMove();
            if (travel == null) return null;
            var pass = context.getPassAfterTravel();
            if (pass == null) pass = new ClientBlockEntityPass(List.of(), player.compensatedWorld.clientGameTime());
            for (var candidate : JavaPassAfterTravel.replay(player, reality, travel, pass, Vec3.ZERO)) {
                var state = candidate.result().state();
                var end = new End(state.position(), state.onGround());
                if (selected != null && !selected.equals(end)) return null;
                selected = end;
            }
        }
        return selected;
    }
}
