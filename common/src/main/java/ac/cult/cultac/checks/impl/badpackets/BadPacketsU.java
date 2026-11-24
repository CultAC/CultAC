package ac.cult.cultac.checks.impl.badpackets;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerInput;

/**
 * LocalPlayer#sendChanges sends PLAYER_INPUT only when the keys differ from lastSentInput, so a packet repeating the
 * last one's keys needs a new LocalPlayer in between: ClientPacketListener#handleLogin and #handleRespawn can start
 * one from Input.EMPTY, which makes the keys still held differ again.
 */
@CheckData(
        name = "BadPacketsU",
        stableKey = "cult.badpackets.unchanged_player_input",
        description = "Sent a player input packet without changing any key",
        experimental = true)
public class BadPacketsU extends Check implements CheckListener {
    private static final ServerboundPlayerInput EMPTY =
            new ServerboundPlayerInput(false, false, false, false, false, false, false);

    // Null until the first packet: the keys a client sent before this player was tracked are unknown.
    private ServerboundPlayerInput last;
    // Whether lastSentInput may be Input.EMPTY instead of last.
    private boolean emptyPossible;
    // The transaction whose pong proves the client handled every login and respawn sent so far.
    private int replacementProof = Integer.MIN_VALUE;

    public BadPacketsU(CultPlayer player) {
        super(player);
    }

    /**
     * A login or respawn was sent, answered no later than {@code proof}. Until that pong a packet may come from the
     * LocalPlayer on either side of it.
     */
    public void localPlayerMayBeReplaced(int proof) {
        emptyPossible = true;
        replacementProof = proof;
    }

    @CultPacketHandler
    public void onPlayerInput(
            PacketReceiveEvent<ServerboundPlayerInput> event, CultPlayer player, ServerboundPlayerInput packet) {
        // An older client's input reaches the server as a translator writes it, each ridden tick.
        if (player.isBedrockMovement() || player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) return;
        // Input.EMPTY is never sent again: every LocalPlayer that did not keep lastSentInput starts from it.
        if (packet.equals(last) && (!emptyPossible || packet.equals(EMPTY))) flag();
        last = packet;
        // A replacement still ahead of the client resets lastSentInput after this packet.
        emptyPossible = player.lastTransactionReceived.get() < replacementProof;
    }
}
