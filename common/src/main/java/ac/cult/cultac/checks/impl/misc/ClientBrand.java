package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.nio.charset.StandardCharsets;
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
        handle(packet.channel(), event.getPacket().data());
    }

    public void handle(String channel, byte[] data) {
        if (!channel.equals(ClientBrand.CHANNEL)) return;

        if (data.length > 64 || data.length == 0) {
            brand = "sent " + data.length + " bytes as brand";
        } else if (!hasBrand) {
            byte[] minusLength = new byte[data.length - 1];
            System.arraycopy(data, 1, minusLength, 0, minusLength.length);
            announce(new String(minusLength));
        }

        hasBrand = true;
    }

    /**
     * Takes a brand the platform read before Cult attached to the connection, such as the one a 1.20.2+ client sends
     * Velocity on entering configuration. A brand already received on the wire wins.
     */
    public void observe(String received) {
        if (hasBrand || received == null) return;
        // The wire form is a VarInt length and the string; the same 64-byte bound applies.
        int length = received.getBytes(StandardCharsets.UTF_8).length + 1;
        if (length > 64) {
            brand = "sent " + length + " bytes as brand";
        } else {
            announce(received);
        }
        hasBrand = true;
    }

    private void announce(String received) {
        brand = received.replace(" (Velocity)", ""); // removes velocity's brand suffix
        brand = MessageUtil.stripColor(brand); // strip color codes from client brand
        if (!CultAPI.INSTANCE.getConfigManager().isIgnoredClient(brand)) {
            String message = CultAPI.INSTANCE
                    .getConfigManager()
                    .getConfig()
                    .getStringElse("client-brand-format", "%prefix% &f%player% joined using %brand%");
            Component component = MessageUtil.replacePlaceholders(player, MessageUtil.miniMessage(message));

            CultAPI.INSTANCE.getAlertManager().sendBrand(component, null);
        }
        // The join-time session upsert runs before the brand packet arrives,
        // so client_brand is null on disk. observeBrandFromCheck re-issues
        // the upsert with the same session_id (idempotent) and the brand
        // filled in.
        CultAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks().observeBrandFromCheck(player);
    }

    /** Keeps the brand this connection already reported to another engine; it was announced there. */
    public void restore(String brand) {
        this.brand = brand;
        hasBrand = true;
    }
}
