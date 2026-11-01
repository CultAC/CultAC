package ac.cult.cultac.companion.apollo;

import ac.cult.cultac.bridge.protocol.BridgeCodec;
import ac.cult.cultac.bridge.protocol.BridgeMessage;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMod;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ClientMods;
import ac.cult.cultac.bridge.protocol.BridgeMessage.ModsRequest;
import ac.cult.cultac.companion.bridge.CompanionBridge;
import ac.cult.cultac.companion.bridge.CompanionMessageHandler;
import ac.cult.cultac.companion.bridge.CompanionSession;
import com.lunarclient.apollo.Apollo;
import com.lunarclient.apollo.client.mod.LunarClientMod;
import com.lunarclient.apollo.event.EventBus;
import com.lunarclient.apollo.event.player.ApolloPlayerHandshakeEvent;
import com.lunarclient.apollo.module.modsetting.ModSettingModule;
import com.lunarclient.apollo.player.ApolloPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks Apollo for a player's installed mods and sends them as CLIENT_MODS: unasked when the bridge opens and when the
 * client completes its Apollo handshake, and in answer to MODS_REQUEST.
 */
final class ApolloMods implements CompanionMessageHandler {
    private final CompanionBridge bridge;
    private final Logger logger;
    private final Consumer<ApolloPlayerHandshakeEvent> handshake = this::onHandshake;
    private volatile boolean active;

    ApolloMods(CompanionBridge bridge, Logger logger) {
        this.bridge = bridge;
        this.logger = logger;
    }

    void register() {
        active = true;
        bridge.addHandler(this);
        EventBus.getBus().register(ApolloPlayerHandshakeEvent.class, handshake);
    }

    /** The bridge keeps its handlers; an inactive one ignores every callback. */
    void unregister() {
        active = false;
        EventBus.getBus().unregister(ApolloPlayerHandshakeEvent.class, handshake);
    }

    @Override
    public void onReady(CompanionSession session) {
        if (active) send(session, ClientMods.PUSHED);
    }

    @Override
    public void onMessage(CompanionSession session, BridgeMessage message) {
        if (active && message instanceof ModsRequest request) send(session, request.requestId());
    }

    private void onHandshake(ApolloPlayerHandshakeEvent event) {
        CompanionSession session = bridge.session(event.getPlayer().getUniqueId());
        if (active && session != null) send(session, ClientMods.PUSHED);
    }

    /** A failed Apollo request answers nothing; the proxy keeps the mods it last received. */
    private void send(CompanionSession session, int requestId) {
        try {
            ApolloPlayer player =
                    Apollo.getPlayerManager().getPlayer(session.playerId()).orElse(null);
            if (player == null) {
                session.send(new ClientMods(requestId, false, List.of()));
                return;
            }
            Apollo.getModuleManager()
                    .getModule(ModSettingModule.class)
                    .requestInstalledMods(player)
                    .onSuccess(response -> {
                        if (session.isReady())
                            session.send(new ClientMods(requestId, true, mods(response.getElements())));
                    })
                    .onFailure(failure ->
                            logger.log(Level.FINE, "Apollo did not report the mods of " + session.playerId(), failure));
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "Unable to request the Lunar Client mods of " + session.playerId(), failure);
        }
    }

    private static List<ClientMod> mods(List<LunarClientMod> installed) {
        List<ClientMod> mods = new ArrayList<>(Math.min(installed.size(), BridgeCodec.MAX_LIST_SIZE));
        for (LunarClientMod mod : installed) {
            if (mods.size() == BridgeCodec.MAX_LIST_SIZE) break;
            if (mod == null || mod.getId() == null) continue;
            mods.add(new ClientMod(
                    mod.getId(),
                    text(mod.getDisplayName()),
                    text(mod.getVersion()),
                    mod.getType() == null ? "UNKNOWN" : mod.getType().name()));
        }
        return mods;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
