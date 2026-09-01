package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.prediction.PredictionResult;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

@CheckData(name = "ElytraI", stableKey = "grim.elytra.water", description = "Started gliding in water", experimental = true)
public class ElytraI extends Check implements PostPredictionListener {
    private boolean setback;

    public ElytraI(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().getProtocolVersion() >= 573; // PE ClientVersion.V_1_15
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!isApplicable()) return;
        if (NmsPacketUtil.readPlayerCommand(packet).action() == NmsPacketUtil.PlayerCommandAction.START_FLYING_WITH_ELYTRA
                && wasTouchingWater()
                && flag()) {
            setback = true;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                resyncPose();
            }
        }
    }


    private boolean wasTouchingWater() {
        PredictionResult lastPrediction = player.checkManager.getSimulationProcessor().getLastPrediction();
        return lastPrediction != null
                && lastPrediction.getSimulationContext().getWorldData().getInWater().determinePessimistically();
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        if (setback) {
            setback = false;
            setbackIfAboveSetbackVL();
        }
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
