package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import lombok.Getter;
import net.kyori.adventure.text.Component;

public class ClientBrand extends Check implements CheckListener {

    private static final String CHANNEL = "minecraft:brand";

    @Getter
    private String brand = "vanilla";

    @Getter
    private boolean hasBrand = false;

    public ClientBrand(CultPlayer player) {
        super(player);
    }

    @CultPacketHandler
    public void onCustomPayload(
            final PacketReceiveEvent<ServerboundCustomPayload> event,
            CultPlayer player,
            ServerboundCustomPayload packet) {
        String channelName = packet.channel();
        if (channelName == null) return;
        handle(channelName, event.getPacket().data());
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
                String message = CultAPI.INSTANCE
                        .getConfigManager()
                        .getConfig()
                        .getStringElse("client-brand-format", "%prefix% &f%player% joined using %brand%");
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

        hasBrand = true;
    }

    /** Keeps the brand this connection already reported to another engine; it was announced there. */
    public void restore(String brand) {
        this.brand = brand;
        hasBrand = true;
    }
}
