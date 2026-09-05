package ac.cult.cultac.checks.impl.sprint;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.PostPredictionListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "SprintD", stableKey = "cult.sprint.blindness", description = "Started sprinting while having blindness", setback = 5, experimental = true)
public class SprintD extends Check implements PostPredictionListener {
    public boolean startedSprintingBeforeBlind = false;

    public SprintD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, CultPlayer player, ServerboundPlayerCommandPacket packet) {
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
