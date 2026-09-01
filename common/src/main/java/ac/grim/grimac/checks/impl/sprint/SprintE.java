package ac.grim.grimac.checks.impl.sprint;

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

@CheckData(name = "SprintE", stableKey = "grim.sprint.wall", description = "Sprinting while colliding with a wall", setback = 5, experimental = true)
public final class SprintE extends Check implements PostPredictionListener {
    private boolean startedSprintingThisTick;
    private boolean wasHardHorizontalCollision;
    private boolean previousPredictionChecked;

    public SprintE(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (NmsPacketUtil.readPlayerCommand(packet).action()
                == NmsPacketUtil.PlayerCommandAction.START_SPRINTING) {
            startedSprintingThisTick = true;
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete complete) {
        if (complete.isTeleport()) {
            wasHardHorizontalCollision = false;
            previousPredictionChecked = false;
            startedSprintingThisTick = false;
            return;
        }

        PredictionResult result = complete.getPredictionResult();
        boolean checked = !complete.isExempt() && result != null;
        boolean inWater = result != null
                && result.getSimulationContext().getWorldData().getInWater().determineOptimistically();

        if (checked && previousPredictionChecked && wasHardHorizontalCollision
                && !startedSprintingThisTick
                && !player.inVehicle()
                && (!inWater || player.getClientVersion().isOlderThan(ClientVersion.V_1_13))) {
            if (player.isSprinting) {
                flagWithSetback();
            } else {
                reward();
            }
        }

        wasHardHorizontalCollision = checked
                && result.getCollideAxisData() != null
                && result.getCollideAxisData().couldCollideHorizontally();
        previousPredictionChecked = checked;
        startedSprintingThisTick = false;
    }
}
