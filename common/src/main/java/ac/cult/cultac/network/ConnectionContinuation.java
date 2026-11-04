package ac.cult.cultac.network;

import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.protocol.value.ParticleStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client state an engine received on a connection that the client does not resend when another engine adopts it at
 * reconfiguration. Crosses engine class loaders as a map of JDK values.
 */
public record ConnectionContinuation(
        UUID connectionId, String brand, ClientInformation information, List<String> channels) {
    public static Map<String, Object> capture(CultConnection connection) {
        var state = new HashMap<String, Object>();
        var user = connection.user();
        if (user != null) state.put("connection-id", user.getConnectionId().toString());
        var player = connection.player();
        if (player == null) return state;
        var brand = player.checkManager.getCheck(ClientBrand.class);
        if (brand != null && brand.isHasBrand()) state.put("brand", brand.getBrand());
        state.put("registered-channels", List.copyOf(player.pluginChannelManager.getRegisteredChannels()));
        var chat = player.checkManager.getCheck(ChatD.class);
        var information = chat == null ? null : chat.information();
        if (information != null) {
            state.put(
                    "client-information",
                    Map.of(
                            "language", information.language(),
                            "view-distance", information.viewDistance(),
                            "chat-visibility", information.chatVisibility().name(),
                            "chat-colors", information.chatColors(),
                            "model-customisation", information.modelCustomisation(),
                            "main-hand", information.mainHand().name(),
                            "text-filtering", information.textFilteringEnabled(),
                            "allows-listing", information.allowsListing(),
                            "particle-status", information.particleStatus().name()));
        }
        return state;
    }

    public static ConnectionContinuation from(Map<String, Object> state) {
        Object id = state.get("connection-id");
        return new ConnectionContinuation(
                id == null ? null : UUID.fromString((String) id),
                (String) state.get("brand"),
                state.get("client-information") instanceof Map<?, ?> fields ? information(fields) : null,
                state.get("registered-channels") instanceof List<?> channels
                        ? channels.stream()
                                .filter(String.class::isInstance)
                                .map(String.class::cast)
                                .toList()
                        : List.of());
    }

    private static ClientInformation information(Map<?, ?> fields) {
        return new ClientInformation(
                (String) fields.get("language"),
                (Integer) fields.get("view-distance"),
                ChatVisibility.valueOf((String) fields.get("chat-visibility")),
                (Boolean) fields.get("chat-colors"),
                (Integer) fields.get("model-customisation"),
                MainHand.valueOf((String) fields.get("main-hand")),
                (Boolean) fields.get("text-filtering"),
                (Boolean) fields.get("allows-listing"),
                ParticleStatus.valueOf((String) fields.get("particle-status")));
    }

    /** Applies the received state to the adopted player before any packet reaches it. */
    void restore(CultPlayer player) {
        channels.forEach(player.pluginChannelManager::handleChannelRegistered);
        var brandCheck = player.checkManager.getCheck(ClientBrand.class);
        if (brand != null && brandCheck != null) brandCheck.restore(brand);
        var chat = player.checkManager.getCheck(ChatD.class);
        if (information != null && chat != null) chat.observe(information);
    }
}
