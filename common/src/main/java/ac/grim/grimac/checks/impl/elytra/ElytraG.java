package ac.grim.grimac.checks.impl.elytra;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "ElytraG", stableKey = "grim.elytra.levitation", description = "Started gliding with levitation", experimental = true)
public class ElytraG extends Check implements PostPredictionListener {
    private boolean setback;

    public ElytraG(GrimPlayer player) {
        super(player);
    }

    @Override
    public boolean isApplicable() {
        return player.getClientVersion().getProtocolVersion() >= 735; // PE ClientVersion.V_1_16
    }

    @GrimPacketHandler
    public void onPlayerCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundPlayerCommandPacket packet) {
        if (!isApplicable()) return;
        if (NmsPacketUtil.readPlayerCommand(packet).action() == NmsPacketUtil.PlayerCommandAction.START_FLYING_WITH_ELYTRA
                && player.compensatedEntities.hasPotionEffect(PotionEffectType.LEVITATION)
                && flag()) {
            setback = true;
            if (shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
                resyncPose();
            }
        }
    }

    @Override
    public void onPredictionComplete(PredictionComplete predictionComplete) {
        if (!isApplicable()) return;
        if (setback) {
            setbackIfAboveSetbackVL();
            setback = false;
        }
    }

    private void resyncPose() {
        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_14) && player.platformPlayer != null) {
            player.platformPlayer.setSneaking(!player.platformPlayer.isSneaking());
        }
    }
}
