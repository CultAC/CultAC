package ac.grim.grimac.checks.impl.sprint;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "SprintD", stableKey = "grim.sprint.blindness", description = "Started sprinting while having blindness", setback = 5, experimental = true)
public class SprintD extends Check implements PostPredictionListener {
    public boolean startedSprintingBeforeBlind = false;

    public SprintD(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (NmsPacketUtil.readPlayerCommand(packet).action() == NmsPacketUtil.PlayerCommandAction.START_SPRINTING) {
            startedSprintingBeforeBlind = false;
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (player.compensatedEntities.hasPotionEffect(PotionEffectType.BLINDNESS) && !startedSprintingBeforeBlind) {
            if (player.isSprinting) {
                flagWithSetback();
            } else reward();
        }
    }
}
