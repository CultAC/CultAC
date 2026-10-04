package ac.cult.cultac.checks.impl.packetorder;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.DecodedPacketReliability;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundInteract;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPlayerAction;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.InteractAction;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.grim.grimac.api.storage.verbose.Verbose;

@CheckData(name = "PacketOrderB", stableKey = "cult.packetorder.noswing", description = "Did not swing for attack")
public class PacketOrderB extends Check implements CheckListener {
    private static final Verbose V = Verbose.of("[pre-attack|post-attack]");

    // 1.9 packet order: INTERACT -> ANIMATION
    // 1.8 packet order: ANIMATION -> INTERACT
    // Both are sent from the same click, before the tick's movement packet and tick end.
    private final boolean is1_9 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);

    private boolean sentAnimationSinceLastAttack = player.getClientVersion().isNewerThan(ClientVersion.V_1_8);
    private boolean sentAttack;
    private boolean sentAnimation;

    public PacketOrderB(final CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onSwing(PacketReceiveEvent<ServerboundSwing> event, CultPlayer player, ServerboundSwing packet) {
        // Minecraft#startAttack always swings the main hand.
        if (packet.hand() != Hand.MAIN_HAND) {
            checkPostAttack();
            return;
        }

        sentAnimationSinceLastAttack = sentAnimation = true;
        sentAttack = false;
    }

    @CultPacketHandler
    public void onInteract(
            PacketReceiveEvent<ServerboundInteract> event, CultPlayer player, ServerboundInteract packet) {
        if (packet.action() == InteractAction.ATTACK
                && DecodedPacketReliability.interactionFamilyReliable(
                        player.getClientVersion(), player.getObservedProtocol())) {
            onAttack(event);
        }
    }

    @CultPacketHandler
    public void onPlayerAction(
            PacketReceiveEvent<ServerboundPlayerAction> event, CultPlayer player, ServerboundPlayerAction packet) {
        if (packet.action() != PlayerAction.STAB) return;

        if (player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_26_3)) {
            // MultiPlayerGameMode#piercingAttack sends STAB and only a
            // local animation. A preceding ordinary attack still needs Punch.
            checkPostAttack();
            sentAnimationSinceLastAttack = true;
            return;
        }
        onAttack(event);
    }

    @CultPacketHandler
    public void onMovePlayer(
            PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
        checkPostAttack();
    }

    @CultPacketHandler("serverbound.client_tick_end")
    public void onClientTickEnd(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
        checkPostAttack();
    }

    // The attack's swing is sent before the tick's movement packet and tick end,
    // and a 1.8 swing only pays for an attack in its own tick.
    private void checkPostAttack() {
        if (sentAttack && is1_9) {
            flag(V.write(verbose()).bool(false));
        }
        sentAttack = sentAnimation = false;
    }

    private void onAttack(PacketReceiveEvent<?> event) {
        if (player.gamemode == GameMode.SPECTATOR
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_11)) {
            return;
        }

        sentAttack = true;
        if (is1_9 ? !sentAnimationSinceLastAttack : !sentAnimation) {
            sentAttack = false;
            if (flag(V.write(verbose()).bool(true)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }
        }

        sentAnimationSinceLastAttack = sentAnimation = false;
    }
}
