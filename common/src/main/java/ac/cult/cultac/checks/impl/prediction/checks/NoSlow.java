package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckInfo;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.IsUsingItem;
import lombok.Setter;

// @CheckData(name = "NoSlow", stableKey = "cult.movement.noslow")
public class NoSlow extends Check implements PostPredictionListener {
    public NoSlow(CultPlayer cultPlayer) {
        super(
                cultPlayer,
                CheckInfo.builder()
                        .name("NoSlow")
                        .stableKey("cult.movement.noslow")
                        .build());
    }

    boolean shouldCheck = false;

    @Setter
    boolean doneSettingMetadata = false;

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) {
            shouldCheck = false;
            if (doneSettingMetadata) {
                player.settingMetaData = false;
                doneSettingMetadata = false;
            }
            if (predictionComplete.getPredictionResult() != null) {
                predictionComplete.getPredictionResult().setExceedsSlowedSpeed(false);
            }
            return;
        }
        if (predictionComplete.isTeleport()
                || predictionComplete.isExempt()
                || player.inVehicle()
                || player.gamemode == GameMode.CREATIVE
                || player.gamemode == GameMode.SPECTATOR) return;
        if (!IsUsingItem.isSlowDueToUsingItem(player)) {
            predictionComplete.getPredictionResult().setExceedsSlowedSpeed(false);
            shouldCheck = false;
            return;
        }

        boolean shouldFlag = true;
        double useSpeedScale = IsUsingItem.getUseItemSpeedMultiplier(player) / 0.2D;
        for (PredictionResult predResult :
                predictionComplete.getPredictionResult().getRealities()) {
            // Let's check the speed of the player first as it's the simplest
            Vec3 closestForSpeed = predResult.getValidMovements().getClosestToTarget();
            Vec3 horizDiffToTarget =
                    predResult.getTarget().subtract(closestForSpeed).multiply(1, 0, 1);

            Vec3 minimumInputRequired = HorizontalAnalyzer.getBestTheoreticalPlayerInput(
                    horizDiffToTarget, predResult.getSimulationContext().getXRot());
            minimumInputRequired = new Vec3(Math.abs(minimumInputRequired.x), 0, Math.abs(minimumInputRequired.z));

            float playerSpeed = predResult.getSimulationContext().getMaxSpeed(player);
            double maxDirLength = playerSpeed * 0.261 * useSpeedScale;
            double speedFlagAmount =
                    Math.max(minimumInputRequired.x - maxDirLength, minimumInputRequired.z - maxDirLength);

            if (speedFlagAmount < 0.001) {
                shouldFlag = false;
                break;
            }
        }

        if (doneSettingMetadata) {
            this.player.settingMetaData = false;
            doneSettingMetadata = false;
        }

        predictionComplete.getPredictionResult().setExceedsSlowedSpeed(shouldFlag);

        if (shouldCheck) {
            shouldCheck = false;
            if (player.checkManager.getSimulationProcessor().getLastTickSkip().hasOccurredSince(1)) {
                return;
            }

            PredictionResult lastResult =
                    player.checkManager.getSimulationProcessor().getLastPrediction();
            if (lastResult.isExceedsSlowedSpeed()) {
                flag();
            }
        }
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (player.platformPlayer == null || !player.platformPlayer.hasServerAuthority()) return;
        if (packet.action() == PlayerAction.RELEASE_USE_ITEM && !player.settingMetaData) {
            shouldCheck = true;
        }
    }
}
