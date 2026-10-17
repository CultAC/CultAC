package ac.cult.cultac.events.packets;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundGameEvent;
import ac.cult.cultac.protocol.value.GameEventType;
import ac.cult.cultac.protocol.value.GameMode;

public class PacketChangeGameState extends CultProcessor implements CheckListener {
    public PacketChangeGameState(CultPlayer playerData) {
        super(playerData);
    }

    @CultPacketHandler
    public void onGameEvent(
            PacketSendEvent<ClientboundGameEvent> event, CultPlayer player, ClientboundGameEvent packet) {
        if (packet.event() != GameEventType.CHANGE_GAME_MODE) {
            return;
        }

        GameMode next = switch ((int) packet.param()) {
            case 1 -> GameMode.CREATIVE;
            case 2 -> GameMode.ADVENTURE;
            case 3 -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        // MultiPlayerGameMode#setLocalMode -> GameType#updatePlayerAbilities. An explicit
        // abilities packet can subsequently overwrite instabuild independently of game mode.
        boolean instabuild = next == GameMode.CREATIVE;
        boolean invulnerable = next == GameMode.CREATIVE || next == GameMode.SPECTATOR;

        player.sendTransaction();
        player.latencyUtils.addRealTimeTaskNow(() -> {
            GameMode previous = player.gamemode;
            player.gamemode = next;
            player.abilitiesSent(instabuild, invulnerable);
            player.compensatedEntities.getSelf().onGameModeChange(player.gamemode == GameMode.CREATIVE);

            if (previous == GameMode.SPECTATOR && player.gamemode != GameMode.SPECTATOR) {
                CultAPI.INSTANCE.getSpectateManager().handlePlayerStopSpectating(player.playerUUID);
            }
        });

        player.runAtTrailingProof(event, () -> player.abilitiesHandled(instabuild, invulnerable));
    }
}
