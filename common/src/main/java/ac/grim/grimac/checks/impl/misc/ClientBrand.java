package ac.grim.grimac.checks.impl.misc;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.type.CheckListener;
import ac.grim.grimac.network.GrimPacketHandler;
import ac.grim.grimac.network.event.PacketReceiveEvent;
import ac.grim.grimac.network.packet.NmsPacketUtil;
import ac.grim.grimac.network.protocol.ClientVersion;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public class ClientBrand extends Check implements CheckListener {

    private static final ClientVersion SERVER_VERSION =
            ClientVersion.fromProtocolVersion(SharedConstants.getProtocolVersion());

    private static final String CHANNEL = SERVER_VERSION.isNewerThanOrEquals(ClientVersion.V_1_13) ? "minecraft:brand" : "MC|Brand";

    @Getter
    private String brand = "vanilla";
    @Getter
    private boolean hasBrand = false;

    public ClientBrand(GrimPlayer player) {
        super(player);
    }

    @GrimPacketHandler
    public void onCustomPayload(final PacketReceiveEvent event, GrimPlayer player, ServerboundCustomPayloadPacket packet) {
        String channelName = NmsPacketUtil.payloadChannel(packet.payload());
        if (channelName == null) return;
        handle(channelName, NmsPacketUtil.payloadData(event));
    }


    public void handle(String channel, byte[] data) {
        if (!channel.equals(ClientBrand.CHANNEL)) return;

        if (data.length > 64 || data.length == 0) {
            brand = "sent " + data.length + " bytes as brand";
        } else if (!hasBrand) {
            byte[] minusLength = new byte[data.length - 1];
            System.arraycopy(data, 1, minusLength, 0, minusLength.length);

            brand = new String(minusLength).replace(" (Velocity)", ""); // removes velocity's brand suffix
            brand = MessageUtil.stripColor(brand); // strip color codes from client brand
            if (!GrimAPI.INSTANCE.getConfigManager().isIgnoredClient(brand)) {
                String message = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("client-brand-format", "%prefix% &f%player% joined using %brand%");
                Component component = MessageUtil.replacePlaceholders(player, MessageUtil.miniMessage(message));

                GrimAPI.INSTANCE.getAlertManager().sendBrand(component, null);
            }
            // Push the now-known brand into the session row. The initial onJoin
            // upsert ran from PlayerJoinEvent, before the brand packet arrived,
            // so client_brand was null on disk. observeBrandFromCheck re-issues
            // the upsert with the same session_id (idempotent) but the brand
            // column now filled in. NOOP impl skips the work entirely.
            GrimAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks().observeBrandFromCheck(player);
        }

        // https://github.com/MinecraftForge/MinecraftForge/issues/9309
        // "Forge 40.1.22 1.18.2+ has extended player reach"
        // quality development from forge devs
        // inbuilt reach hacks for over a year across 2 (3 if you include 1.19.3/1.20) major versions
        // Fixed in 1.19.4 possibly? Definitely fixed in 1.20+.
        final boolean hasReachHacks = brand.contains("forge")
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2)
                && player.getClientVersion().isOlderThan(ClientVersion.V_1_19_4);
        if (hasReachHacks && GrimAPI.INSTANCE.getConfigManager().isBlockBlacklistedForgeClients()) {
            player.disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(player, GrimAPI.INSTANCE.getConfigManager().getDisconnectBlacklistedForge())));
        }

        hasBrand = true;
    }
}
