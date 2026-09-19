package ac.cult.cultac.events.packets.listeners;

import ac.cult.cultac.bedrock.bridge.GeyserBedrockBridgeRuntime;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputPluginMessage;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** Completes the local translation barrier; original input is processed on the Bedrock loop. */
public final class BedrockAuthInputPluginMessageListener {
    @CultPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, ac.cult.cultac.player.CultPlayer player,
                                ServerboundCustomPayloadPacket packet) {
        String channel = NmsPacketUtil.payloadChannel(NmsPacketUtil.payload(packet));
        if (BedrockAuthInputPluginMessage.CHANNEL.equals(channel)
                || GeyserBedrockBridgeRuntime.finishTranslation(event.getUser(), channel, () -> NmsPacketUtil.payloadData(event))) {
            event.setCancelled(true);
        }
    }
}
