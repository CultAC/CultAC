package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

@CheckData(name = "BadPacketsM", stableKey = "cult.badpackets.respawn_alive", description = "Tried to respawn while alive", experimental = true)
public class BadPacketsM extends Check implements CheckListener {
    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    public BadPacketsM(final CultPlayer player) {
        super(player);
    }

    // not a boolean because the server could send packets that cause
    // the client to send a respawn packet before it receives the first
    private int exempt;
    private boolean menu;

    @CultPacketHandler
    public void onClientCommand(PacketReceiveEvent event, CultPlayer player, ServerboundClientCommandPacket packet) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) {
            return;
        }

        if (exempt > 0) {
            exempt--;
            return;
        }

        if (!player.compensatedEntities.getSelf().isDead && !menu) {
            flag(); // don't cancel in case of a false positive
        }

        // the client closes the menu and reopens it if dead
        menu = player.compensatedEntities.getSelf().isDead && player.packetStateData.showsDeathScreen;
    }

    @CultPacketHandler
    public void onGameEvent(PacketSendEvent event, CultPlayer player, ClientboundGameEventPacket packet) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
            return;
        }

        if (packet.getEvent() == ClientboundGameEventPacket.WIN_GAME) {
            if (packet.getParam() != 0 && packet.getParam() != 1) {
                return; // client ignores this
            }

            player.sendTransaction();
            player.latencyUtils.addRealTimeTaskNow(() -> {
                // we COULD get a death combat packet while the credits are rolling, (IF packet.getParam() == 1)
                // but this can only cause at most one false negative (for each of this packet sent)
                exempt++;
                menu = false;
            });
        }


        if (packet.getEvent() == ClientboundGameEventPacket.IMMEDIATE_RESPAWN) {
            if (player.getClientVersion().getProtocolVersion() < 573 // PE ClientVersion.V_1_15
                    || SERVER_VERSION.getProtocolVersion() < 573) { // PE ServerVersion.V_1_15
                return;
            }

            player.sendTransaction();
            final boolean enabled = packet.getParam() == 0f;
            player.latencyUtils.addRealTimeTaskNow(() -> player.packetStateData.showsDeathScreen = enabled);
        }
    }


    @CultPacketHandler
    public void onPlayerCombatKill(PacketSendEvent event, CultPlayer player, ClientboundPlayerCombatKillPacket packet) {
        if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
            return;
        }

        if (packet.playerId() == player.entityID) {
            player.sendTransaction();
            player.latencyUtils.addRealTimeTaskNow(this::onDeathCombatEvent);
        }
    }

    private void onDeathCombatEvent() {
        if (player.packetStateData.showsDeathScreen) {
            menu = true;
        } else {
            exempt++;
        }
    }

    public void onRespawn() {
        menu = false; // the client closes any open screens on respawn
    }

    // the menu is actually kept when the player's health is set to >0
    public void onDeath() {
        if (player.packetStateData.showsDeathScreen) {
            menu = true;
        }
    }
}
