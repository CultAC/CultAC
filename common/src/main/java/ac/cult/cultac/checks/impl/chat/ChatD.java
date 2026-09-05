package ac.cult.cultac.checks.impl.chat;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.CheckData;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.world.entity.player.ChatVisiblity;

@CheckData(name = "ChatD", stableKey = "cult.exploit.chat_while_hidden", description = "Chatting while chat is hidden")
public class ChatD extends Check implements CheckListener {
    private boolean hidden;

    public ChatD(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onChatMessage(PacketReceiveEvent event, CultPlayer player, ServerboundChatPacket packet) {
        check(event);
    }

    @CultPacketHandler
    public void onChatCommandUnsigned(PacketReceiveEvent event, CultPlayer player, ServerboundChatCommandSignedPacket packet) {
        check(event);
    }

    @CultPacketHandler
    public void onChatCommand(PacketReceiveEvent event, CultPlayer player, ServerboundChatCommandPacket packet) {
        check(event);
    }

    private void check(PacketReceiveEvent event) {
        if (hidden && flag() && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    @CultPacketHandler
    public void onClientInformation(PacketReceiveEvent event, CultPlayer player, ServerboundClientInformationPacket packet) {

        hidden = packet.information().chatVisibility() == ChatVisiblity.HIDDEN;
    }
}
