package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.packet.NmsPacketUtil;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.anticheat.MessageUtil;
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

    public ClientBrand(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onCustomPayload(final PacketReceiveEvent event, CultPlayer player, ServerboundCustomPayloadPacket packet) {
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
            if (!CultAPI.INSTANCE.getConfigManager().isIgnoredClient(brand)) {
                String message = CultAPI.INSTANCE.getConfigManager().getConfig().getStringElse("client-brand-format", "%prefix% &f%player% joined using %brand%");
                Component component = MessageUtil.replacePlaceholders(player, MessageUtil.miniMessage(message));

                CultAPI.INSTANCE.getAlertManager().sendBrand(component, null);
            }
            // Push the now-known brand into the session row. The initial onJoin
            // upsert ran from PlayerJoinEvent, before the brand packet arrived,
            // so client_brand was null on disk. observeBrandFromCheck re-issues
            // the upsert with the same session_id (idempotent) but the brand
            // column now filled in. NOOP impl skips the work entirely.
            CultAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks().observeBrandFromCheck(player);
        }

        // https://github.com/MinecraftForge/MinecraftForge/issues/9309
        // "Forge 40.1.22 1.18.2+ has extended player reach"
        // quality development from forge devs
        // inbuilt reach hacks for over a year across 2 (3 if you include 1.19.3/1.20) major versions
        // Fixed in 1.19.4 possibly? Definitely fixed in 1.20+.
        final boolean hasReachHacks = brand.contains("forge")
                && player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_18_2)
                && player.getClientVersion().isOlderThan(ClientVersion.V_1_19_4);
        if (hasReachHacks && CultAPI.INSTANCE.getConfigManager().isBlockBlacklistedForgeClients()) {
            player.disconnect(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(player, CultAPI.INSTANCE.getConfigManager().getDisconnectBlacklistedForge())));
        }

        hasBrand = true;
    }
}
