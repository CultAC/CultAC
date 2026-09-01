package ac.grim.grimac.checks.impl.chat;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.player.GrimPlayer;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.world.entity.player.ChatVisiblity;

@CheckData(name = "ChatD", stableKey = "grim.exploit.chat_while_hidden", description = "Chatting while chat is hidden")
public class ChatD extends Check implements CheckListener {
    private boolean hidden;

    public ChatD(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onChatMessage(PacketReceiveEvent event, GrimPlayer player, ServerboundChatPacket packet) {
        check(event);
    }

    @GrimPacketHandler
    public void onChatCommandUnsigned(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandSignedPacket packet) {
        check(event);
    }

    @GrimPacketHandler
    public void onChatCommand(PacketReceiveEvent event, GrimPlayer player, ServerboundChatCommandPacket packet) {
        check(event);
    }

    private void check(PacketReceiveEvent event) {
        if (hidden && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @GrimPacketHandler
    public void onClientInformation(PacketReceiveEvent event, GrimPlayer player, ServerboundClientInformationPacket packet) {

        hidden = packet.information().chatVisibility() == ChatVisiblity.HIDDEN;
    }
}
