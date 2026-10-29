package ac.cult.cultac.checks.impl.misc;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.checks.Check;
import ac.cult.cultac.checks.type.CheckListener;
import ac.cult.cultac.manager.alerts.AlertSubject;
import ac.cult.cultac.network.ClientBrandText;
import ac.cult.cultac.network.CultPacketHandler;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.routing.NetworkModule;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.utils.anticheat.MessageUtil;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

public class ClientBrand extends Check implements CheckListener {

    private static final String CHANNEL = "minecraft:brand";

    @Getter
    private String brand = "vanilla";

    @Getter
    private boolean hasBrand = false;

    /** On a proxy, the brand message waits for the player's first backend, whose staff it is for. */
    private final AtomicReference<Component> heldAnnouncement = new AtomicReference<>();

    /** The sanitized brand last reported to the network for this connection, null before the first. */
    private @Nullable String reportedBrand;

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

    /** The proxy names the backend before it forwards that backend's login. */
    @CultPacketHandler
    public void onLogin(PacketSendEvent<ClientboundLogin> event, CultPlayer player, ClientboundLogin packet) {
        Component held = heldAnnouncement.getAndSet(null);
        if (held != null) CultAPI.INSTANCE.getAlertManager().sendBrand(player, held, null);
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
        report(payload(data));
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
        report(received);
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

            if (CultAPI.INSTANCE.getPlatform() == Platform.VELOCITY
                    && AlertSubject.of(player).server() == null) {
                heldAnnouncement.set(component);
            } else {
                CultAPI.INSTANCE.getAlertManager().sendBrand(player, component, null);
            }
        }
        // The join-time session upsert runs before the brand packet arrives,
        // so client_brand is null on disk. observeBrandFromCheck re-issues
        // the upsert with the same session_id (idempotent) and the brand
        // filled in.
        CultAPI.INSTANCE.getDataStoreLifecycle().liveWriteHooks().observeBrandFromCheck(player);
    }

    /** Tells the network each brand this connection sends that differs from the one it last reported. */
    private void report(@Nullable String received) {
        String sanitized = ClientBrandText.sanitize(received);
        if (sanitized == null || sanitized.equals(reportedBrand)) return;
        reportedBrand = sanitized;
        NetworkModule.brandChanged(player.getUniqueId(), sanitized);
    }

    /** The string of a brand payload, a VarInt length and UTF-8 text, cut to the bytes present; null when malformed. */
    private static @Nullable String payload(byte[] data) {
        int length = 0;
        for (int index = 0; index < Math.min(data.length, 5); index++) {
            byte read = data[index];
            length |= (read & 0x7F) << (7 * index);
            if ((read & 0x80) == 0) {
                int start = index + 1;
                int end = length < 0 || length > data.length - start ? data.length : start + length;
                return new String(data, start, end - start, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Keeps the brand this connection already reported to another engine; it was announced there. */
    public void restore(String brand) {
        this.brand = brand;
        hasBrand = true;
    }
}
