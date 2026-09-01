package ac.grim.grimac.checks.impl.prediction.checks;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckInfo;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.nmsutil.IsUsingItem;
import org.bukkit.GameMode;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import lombok.Setter;

//@CheckData(name = "NoSlow", stableKey = "grim.movement.noslow")
public class NoSlow extends Check implements PostPredictionListener {
    public NoSlow(GrimPlayer grimPlayer) {
        super(grimPlayer, CheckInfo.builder().name("NoSlow").stableKey("grim.movement.noslow").build());
    }

    boolean shouldCheck = false;
    @Setter
    boolean doneSettingMetadata = false;

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {

        if (predictionComplete.isTeleport() || predictionComplete.isExempt() || player.inVehicle() || player.gamemode == GameMode.CREATIVE || player.gamemode == GameMode.SPECTATOR) return;
        if (!IsUsingItem.isSlowDueToUsingItem(player)) {
            predictionComplete.getPredictionResult().setExceedsSlowedSpeed(false);
            shouldCheck = false;
            return;
        }

        boolean shouldFlag = true;
        double useSpeedScale = IsUsingItem.getUseItemSpeedMultiplier(player) / 0.2D;
        for (PredictionResult predResult : predictionComplete.getPredictionResult().getRealities()) {
            // Let's check the speed of the player first as it's the simplest
            Vec3 closestForSpeed = predResult.getValidMovements().getClosestToTarget();
            Vec3 horizDiffToTarget = predResult.getTarget().subtract(closestForSpeed).multiply(1, 0, 1);

            Vec3 minimumInputRequired = HorizontalAnalyzer.getBestTheoreticalPlayerInput(horizDiffToTarget, predResult.getSimulationContext().getXRot());
            minimumInputRequired = new Vec3(Math.abs(minimumInputRequired.x), 0, Math.abs(minimumInputRequired.z));

            float playerSpeed = predResult.getSimulationContext().getMaxSpeed(player);
            double maxDirLength = playerSpeed * 0.261 * useSpeedScale;
            double speedFlagAmount = Math.max(minimumInputRequired.x - maxDirLength, minimumInputRequired.z - maxDirLength);

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

            PredictionResult lastResult = player.checkManager.getSimulationProcessor().getLastPrediction();
            if (lastResult.isExceedsSlowedSpeed()) {
                flag();
            }
        }
    }

    @GrimPacketHandler
    public void onPlayerAction(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerActionPacket packet) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM && !player.settingMetaData) {
            shouldCheck = true;
        }
    }
}
