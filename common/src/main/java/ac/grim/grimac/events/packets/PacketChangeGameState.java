package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.bukkit.GameMode;

public class PacketChangeGameState extends GrimProcessor implements CheckListener {
    public PacketChangeGameState(GrimPlayer playerData) {
        super(playerData);
    }

    @GrimPacketHandler
    public void onGameEvent(PacketSendEvent event, GrimPlayer player, ClientboundGameEventPacket packet) {
        if (packet.getEvent() != ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            return;
        }

        player.sendTransaction();
        player.latencyUtils.addRealTimeTaskNow(() -> { GameMode previous = player.gamemode;
            int gamemode = (int) packet.getParam();
            player.gamemode = switch (gamemode) {
                case 1 -> GameMode.CREATIVE;
                case 2 -> GameMode.ADVENTURE;
                case 3 -> GameMode.SPECTATOR;
                default -> GameMode.SURVIVAL;
            };
            player.compensatedEntities.getSelf().setDefaultBlockInteractionRange(player.gamemode == GameMode.CREATIVE);

            if (previous == GameMode.SPECTATOR && player.gamemode != GameMode.SPECTATOR) {
                GrimAPI.INSTANCE.getSpectateManager().handlePlayerStopSpectating(player.playerUUID);
            }
        });
    }
}
