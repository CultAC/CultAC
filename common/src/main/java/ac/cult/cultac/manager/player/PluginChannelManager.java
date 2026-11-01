package ac.cult.cultac.manager.player;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.CultProcessor;
import ac.cult.cultac.checks.impl.exploit.ExploitC;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import lombok.Getter;

/**
 * The plugin channels a client declared with minecraft:register (removed again by minecraft:unregister), and the
 * channels it actually sent payloads on.
 */
@Getter
public class PluginChannelManager extends CultProcessor implements CheckListener {
    /** Distinct channels a client may send payloads on before ExploitC flags it. */
    static final int MAX_PAYLOAD_CHANNELS = 96;
    /** Declared channels kept; Velocity accepts up to 1024 from one client, and declaring many is legitimate. */
    static final int MAX_REGISTERED_CHANNELS = 1024;

    public PluginChannelManager(CultPlayer cultPlayer) {
        super(cultPlayer);
    }

    private final Set<String> registeredChannels = new LinkedHashSet<>();
    private final Set<String> payloadChannels = new LinkedHashSet<>();
    private String brand = null;

    @CultPacketHandler
    public void onCustomPayload(
            PacketReceiveEvent<ServerboundCustomPayload> event, CultPlayer player, ServerboundCustomPayload packet) {
        if (event.isCancelled()) {
            return;
        }
        String channel = packet.channel();
        handle(channel, event.getPacket().data(), event);
    }

    public void handle(String channelName, byte[] payload, PacketReceiveEvent event) {
        if (SmoketestControlBridge.enabled()) {
            if (SmoketestControlBridge.handle(player, channelName, payload)) {
                return;
            }
            if (SmoketestSnapshotBridge.handle(player, channelName, payload)) {
                return;
            }
            if (SmoketestBlockEvidenceBridge.handle(player, channelName, payload)) {
                return;
            }
        }

        final int length = payload.length;
        // check if the data sent is too large
        if (length > 8192) {
            player.checkManager.getListener(ExploitC.class).flag("Plugin message data too large: " + length);
            event.setCancelled(true);
            player.onPacketCancel();
            return;
        }
        if (isRegister(channelName)) {
            for (String channel : channelList(payload)) handleChannelRegistered(channel);
            return;
        }
        if (isUnregister(channelName)) {
            for (String channel : channelList(payload)) handleChannelUnregistered(channel);
            return;
        }
        if (!isBrand(channelName) && !handlePayloadChannel(channelName)) {
            return;
        }
        // handle the brand
        if (isBrand(channelName) && payload.length > 0) {
            if (this.brand != null) {
                // TODO: Brand spoof check again
                // player.checkManager.getListener(BrandSpoofA.class).flag("Sent multiple brands");
                return;
            }
            updateBrand(readBrandPayload(payload));
        }
    }

    /** A channel the client declared it listens on. */
    public void handleChannelRegistered(String channel) {
        if (channel == null || channel.isBlank() || registeredChannels.size() >= MAX_REGISTERED_CHANNELS) {
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

    /** Records a channel the client sent a payload on; false when ExploitC flagged it instead. */
    private boolean handlePayloadChannel(String channel) {
        if (channel.isBlank() || payloadChannels.contains(channel)) {
            return true;
        }
        int total = payloadChannels.size() + 1;
        if (total > MAX_PAYLOAD_CHANNELS) {
            player.checkManager.getListener(ExploitC.class).flag("Too many plugin channels used: " + total);
            return false;
        }
        payloadChannels.add(channel);
        return true;
    }

    /** Every channel the client declared or used, sorted. */
    public SortedSet<String> channels() {
        SortedSet<String> channels = new TreeSet<>(registeredChannels);
        channels.addAll(payloadChannels);
        return channels;
    }

    private static boolean isBrand(String channel) {
        return "minecraft:brand".equals(channel);
    }

    private static boolean isRegister(String channel) {
        return "minecraft:register".equals(channel);
    }

    private static boolean isUnregister(String channel) {
        return "minecraft:unregister".equals(channel);
    }

    /** The NUL-separated channel ids of a register or unregister payload. */
    static List<String> channelList(byte[] payload) {
        List<String> channels = new ArrayList<>();
        for (String channel : new String(payload, StandardCharsets.UTF_8).split("\0")) {
            if (!channel.isBlank()) channels.add(channel);
        }
        return channels;
    }

    private void updateBrand(String brand) {
        if (brand == null || brand.isBlank() || this.brand != null) {
            return;
        }
        this.brand = CultAPI.INSTANCE.getConfigManager().simplifyBrand(brand);
    }

    private String readBrandPayload(byte[] data) {
        byte[] minusLength = new byte[data.length - 1];
        System.arraycopy(data, 1, minusLength, 0, minusLength.length);
        return new String(minusLength);
    }
}
