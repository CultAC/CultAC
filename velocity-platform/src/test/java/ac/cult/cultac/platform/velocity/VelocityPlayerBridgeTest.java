package ac.cult.cultac.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ac.cult.cultac.bridge.protocol.BridgeMessage.ClearActiveItem;
import ac.cult.cultac.bridge.protocol.ServerCapability;
import ac.cult.cultac.network.CultConnection;
import ac.cult.cultac.platform.velocity.bridge.BridgeMessageListener;
import ac.cult.cultac.platform.velocity.bridge.BridgeSession;
import ac.cult.cultac.platform.velocity.bridge.BridgeSessions;
import ac.cult.cultac.platform.velocity.bridge.VelocityBridge;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.Hand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VelocityPlayerBridgeTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private final UUID id = UUID.randomUUID();
    private final Player nativePlayer = mock(Player.class);
    private final BridgeSession session = mock(BridgeSession.class);
    private final CultPlayer actor = mock(CultPlayer.class, RETURNS_DEEP_STUBS);
    private final ServerConnection lobby = server("lobby");
    private VelocityPlayer player;
    private @Nullable BridgeSession current;

    @BeforeEach
    void bridge() {
        when(nativePlayer.getUniqueId()).thenReturn(id);
        when(nativePlayer.getCurrentServer()).thenAnswer(invocation -> Optional.of(lobby));
        player = new VelocityPlayer(nativePlayer, mock(VelocitySenders.class));
        CultConnection connection = mock(CultConnection.class);
        when(connection.player()).thenReturn(actor);
        player.attach(connection);
        actor.world = OVERWORLD;
        when(session.isReady()).thenReturn(true);
        when(session.has(any())).thenReturn(true);
        current = session;
        VelocityBridge.install(new BridgeSessions() {
            @Override
            public @Nullable BridgeSession current(UUID player) {
                return id.equals(player) ? current : null;
            }

            @Override
            public void addListener(BridgeMessageListener listener) {}
        });
    }

    @AfterEach
    void reset() {
        VelocityBridge.install(BridgeSessions.NONE);
    }

    private static ServerConnection server(String name) {
        ServerConnection server = mock(ServerConnection.class, RETURNS_DEEP_STUBS);
        when(server.getServerInfo().getName()).thenReturn(name);
        return server;
    }

    @Test
    void clearActiveItemSendsTheHandAndItemType() {
        player.clearActiveItem(Hand.OFF_HAND, "minecraft:shield");
        player.clearActiveItem(Hand.MAIN_HAND, "minecraft:bread");
        verify(session).send(new ClearActiveItem(ClearActiveItem.OFF_HAND, "minecraft:shield"));
        verify(session).send(new ClearActiveItem(ClearActiveItem.MAIN_HAND, "minecraft:bread"));
    }

    @Test
    void clearActiveItemNeedsTheClearCapability() {
        when(session.has(any())).thenReturn(false);
        player.clearActiveItem(Hand.OFF_HAND, "minecraft:shield");
        verify(session, never()).send(any());

        when(session.has(ServerCapability.CLEAR_ACTIVE_ITEM)).thenReturn(true);
        player.clearActiveItem(Hand.OFF_HAND, "minecraft:shield");
        verify(session).send(new ClearActiveItem(ClearActiveItem.OFF_HAND, "minecraft:shield"));
    }

    @Test
    void worldUidOnTheSameServerIsTheLiveMirror() {
        UUID uid = UUID.randomUUID();
        when(session.worldUid()).thenReturn(uid);
        VelocityWorld world = player.getWorld();

        assertEquals(uid, world.getUID());
        assertEquals(uid, world.uidOn(lobby));
        assertEquals(uid, world.uidOn(server("lobby")));
        assertNull(world.uidOn(server("survival")));
        assertNull(world.uidOn(null));
    }

    @Test
    void recordedMirrorOutlivesTheSession() {
        UUID uid = UUID.randomUUID();
        VelocityWorld world = player.getWorld();
        world.mirror(uid);

        current = null;
        assertNull(world.getUID());
        assertEquals(uid, world.uidOn(lobby));
        assertNull(world.uidOn(server("survival")));
    }
}
