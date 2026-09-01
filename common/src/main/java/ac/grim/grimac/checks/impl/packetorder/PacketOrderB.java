package ac.grim.grimac.checks.impl.packetorder;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.LegacyPacketEventSemantics;
import ac.grim.grimac.checks.type.OrderedPacketReceiveListener;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.DecodedPacketReliability;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import org.bukkit.GameMode;

@CheckData(name = "PacketOrderB", stableKey = "grim.packetorder.noswing", description = "Did not swing for attack")
public class PacketOrderB extends Check implements OrderedPacketReceiveListener {
    private static final Verbose V = Verbose.of("[pre-attack|post-attack]");
    private static final String STANDALONE_ATTACK_PACKET =
            "net.minecraft.network.protocol.game.ServerboundAttackPacket";

    // 1.9 packet order: INTERACT -> ANIMATION
    // 1.8 packet order: ANIMATION -> INTERACT
    private final boolean is1_9 = player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_9);

    private boolean sentAnimationSinceLastAttack = player.getClientVersion().isNewerThan(ClientVersion.V_1_8);
    private boolean sentAttack;
    private boolean sentAnimation;
    private boolean sentSlotSwitch;

    public PacketOrderB(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getNmsPacket();

        if (packet instanceof ServerboundSwingPacket swing
                && swing.getHand() == InteractionHand.MAIN_HAND) {
            sentAnimationSinceLastAttack = sentAnimation = true;
            sentAttack = sentSlotSwitch = false;
            return;
        }

        if (packet instanceof ServerboundInteractPacket interact
                && NmsPacketUtil.readInteract(interact).action() == NmsPacketUtil.InteractAction.ATTACK) {
            if (DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) {
                onAttack(event);
                return;
            }
        }

        if (packet.getClass().getName().equals(STANDALONE_ATTACK_PACKET)) {
            if (DecodedPacketReliability.interactionFamilyReliable(player.getClientVersion())) {
                onAttack(event);
                return;
            }
        }

        if (packet instanceof ServerboundPlayerActionPacket actionPacket
                && actionPacket.getAction() == ServerboundPlayerActionPacket.Action.STAB) {
            onAttack(event);
            return;
        }

        if (packet instanceof ServerboundSetCarriedItemPacket && !is1_9 && !sentSlotSwitch) {
            sentSlotSwitch = true;
            return;
        }

        if (!LegacyPacketEventSemantics.isAsync(packet)) {
            if (sentAttack && is1_9) {
                flag(V.write(verbose()).bool(false));
            }
            sentAttack = sentAnimation = sentSlotSwitch = false;
        }
    }

    private void onAttack(PacketReceiveEvent event) {
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

        sentAnimationSinceLastAttack = sentAnimation = sentSlotSwitch = false;
    }
}
