package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.checks.impl.prediction.stage.world.WorldData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.FlightToggle;

@CheckData(
        name = "SprintB",
        stableKey = "cult.sprint.sneaking",
        description = "Sprinting while sneaking or crawling",
        setback = 5,
        experimental = true)
public final class SprintB extends Check implements PostPredictionListener {
    public SprintB(CultPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        // MC-152728 permits sprinting while sneaking on modern versions. The
        // 1.21.4 regression is the sole modern exception retained by Cult 2.0.
        return player.getClientVersion() == ClientVersion.V_1_21_4;
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (!isApplicable() || complete.isTeleport() || complete.isExempt()) return;

        PredictionResult result = complete.getPredictionResult();
        if (result == null || !crouchingWithItsEyeAboveWater(result.getSimulationContext())) return;

        if (player.isSprinting) {
            flagWithSetback();
        } else {
            reward();
        }
    }

    /**
     * LocalPlayer#aiStep crouches a player whose previous keys held shift unless it is flying, swimming or riding, and
     * crouching stops sprinting; only a player whose eye was under water (LocalPlayer#isUnderWater) starts it again.
     */
    private boolean crouchingWithItsEyeAboveWater(SimulationContext context) {
        WorldData world = context.getWorldData();
        if (!context.isLastSneaking()
                || world.isSwimming()
                || world.isUnderwater()
                || FlightToggle.flyingBeforeAiStep(player)
                || context.getVehicle() != null
                || !world.getInWater().determinePessimistically()) {
            return false;
        }
        Vec3 from = context.getStart();
        return player.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING, from.x, from.y, from.z);
    }
}
