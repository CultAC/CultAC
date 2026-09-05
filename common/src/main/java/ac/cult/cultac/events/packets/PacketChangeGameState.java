package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.network.event.PacketSendEvent;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import org.bukkit.GameMode;

public class PacketChangeGameState extends CultProcessor implements CheckListener {
    public PacketChangeGameState(CultPlayer playerData) {
        super(playerData);
    }

    @CultPacketHandler
    public void onGameEvent(PacketSendEvent event, CultPlayer player, ClientboundGameEventPacket packet) {
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
                CultAPI.INSTANCE.getSpectateManager().handlePlayerStopSpectating(player.playerUUID);
            }
        });
    }
}
