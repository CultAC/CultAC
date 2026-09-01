package ac.grim.grimac.manager.player;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.exploit.ExploitC;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import lombok.Getter;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

@Getter
public class PluginChannelManager extends GrimProcessor implements CheckListener {
    public PluginChannelManager(GrimPlayer grimPlayer) { super(grimPlayer); }

    private final Set<String> registeredChannels = new HashSet<>();
    private String brand = null;

    @GrimPacketHandler
    public void onCustomPayload(PacketReceiveEvent event, GrimPlayer player, ServerboundCustomPayloadPacket packet) {
        if (event.isCancelled()) {
            return;
        }
        String channel = NmsPacketUtil.payloadChannel(NmsPacketUtil.payload(packet));
        if (channel == null) {
            return;
        }
        if (!"minecraft:brand".equals(channel) && !"MC|Brand".equals(channel)) {
            handleChannelRegistered(channel);
        }
        handle(channel, NmsPacketUtil.payloadData(event), event);
    }

    public void handle(String channelName, byte[] payload, PacketReceiveEvent event) {
        if (SmoketestControlBridge.enabled()) {
            if (SmoketestControlBridge.handle(player, channelName, payload)) {
                return;
            }
            if (SmoketestSnapshotBridge.handle(player, channelName, payload)) {
                return;
            }
        }

        final int length = payload.length;
        //check if the data sent is too large
        if (length > 8192) { player.checkManager.getListener(ExploitC.class).flag("Plugin message data too large: " + length);
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }
        //handle the brand
        if (("minecraft:brand".equals(channelName) || "MC|Brand".equals(channelName)) && payload.length > 0) {
            if (this.brand != null) {
                // TODO: Brand spoof check again
                //player.checkManager.getListener(BrandSpoofA.class).flag("Sent multiple brands");
                return;
            }
            updateBrand(readBrandPayload(payload));
        }
    }

    public void refreshFromBukkit(Player bukkitPlayer) {
        if (bukkitPlayer == null) {
            return;
        }
        if (this.brand == null) {
            String clientBrand = bukkitPlayer.getClientBrandName();
            if (clientBrand != null && !clientBrand.isBlank()) {
                updateBrand(clientBrand);
            }
        }
        Set<String> currentChannels = new HashSet<>(bukkitPlayer.getListeningPluginChannels());
        for (String channel : Set.copyOf(registeredChannels)) {
            if (!currentChannels.contains(channel)) {
                handleChannelUnregistered(channel);
            }
        }
        for (String channel : currentChannels) {
            handleChannelRegistered(channel);
        }
    }

    public void handleChannelRegistered(String channel) {
        if (channel == null || channel.isBlank() || registeredChannels.contains(channel)) {
            return;
        }
        int total = registeredChannels.size() + 1;
        if (total > 96) { player.checkManager.getListener(ExploitC.class).flag("Too many plugin channels registered: " + total);
            return;
        }
        registeredChannels.add(channel);
    }

    public void handleChannelUnregistered(String channel) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        registeredChannels.remove(channel);
    }

    private void updateBrand(String brand) {
        if (brand == null || brand.isBlank() || this.brand != null) {
            return;
        }
        this.brand = GrimAPI.INSTANCE.getConfigManager().simplifyBrand(brand);
    }

    private String readBrandPayload(byte[] data) {
        byte[] minusLength = new byte[data.length - 1];
        System.arraycopy(data, 1, minusLength, 0, minusLength.length);
        return new String(minusLength);
    }

}
