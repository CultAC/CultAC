package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.chat.ChatD;
import ac.cult.cultac.checks.impl.misc.ClientBrand;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.ChatVisibility;
import ac.cult.cultac.protocol.value.ClientInformation;
import ac.cult.cultac.protocol.value.MainHand;
import ac.cult.cultac.protocol.value.ParticleStatus;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectionContinuationTest {
    private static final UUID PLAYER = UUID.fromString("0f6f7b54-6f5e-4c43-9a4a-0b2c9f9b2d11");

    @Test
    void adoptedConnectionKeepsBrandClientInformationAndSession() {
        OfflineCultTestBootstrap.installConfig();
        var information = new ClientInformation(
                "de_de", 7, ChatVisibility.HIDDEN, false, 0x7f, MainHand.LEFT, true, false, ParticleStatus.MINIMAL);
        var previous = player(TestUsers.create(profile(), new EmbeddedChannel()));
        previous.checkManager.getCheck(ClientBrand.class).restore("fabric");
        previous.checkManager.getCheck(ChatD.class).observe(information);

        // The host copies the released state between engine class loaders.
        var state = Map.copyOf(ConnectionContinuation.capture(previous.user.getCultConnection()));
        assertTrue(state.values().stream().allMatch(ConnectionContinuationTest::jdkValue));

        var session = new CultConnection(
                new EmbeddedChannel(), CultAPI.INSTANCE.getNetworkManager().dispatcher(), ignored -> null);
        var continued = ConnectionContinuation.from(state);
        session.continuation(continued);
        var adopted = player(new User(profile(), session));
        continued.restore(adopted);

        assertEquals(previous.user.getConnectionId(), adopted.user.getConnectionId());
        assertEquals("fabric", adopted.getBrand());
        assertTrue(adopted.checkManager.getCheck(ClientBrand.class).isHasBrand());
        assertEquals(information, adopted.checkManager.getCheck(ChatD.class).information());
    }

    @Test
    void connectionWithoutReceivedStateStartsFresh() {
        OfflineCultTestBootstrap.installConfig();
        var previous = player(TestUsers.create(profile(), new EmbeddedChannel()));
        var continued = ConnectionContinuation.from(
                Map.copyOf(ConnectionContinuation.capture(previous.user.getCultConnection())));
        assertNull(continued.brand());
        assertNull(continued.information());
        assertEquals(previous.user.getConnectionId(), continued.connectionId());
    }

    @Test
    void brandReadBeforeAttachDoesNotReplaceAReceivedOne() {
        OfflineCultTestBootstrap.installConfig();
        var received = player(TestUsers.create(profile(), new EmbeddedChannel()));
        var wire = received.checkManager.getCheck(ClientBrand.class);
        wire.restore("fabric");
        wire.observe("vanilla");
        assertEquals("fabric", received.getBrand());

        var unknown = player(TestUsers.create(profile(), new EmbeddedChannel()));
        unknown.checkManager.getCheck(ClientBrand.class).observe(null);
        assertFalse(unknown.checkManager.getCheck(ClientBrand.class).isHasBrand());

        // The wire bound applies to a brand the platform read: a VarInt length and the string within 64 bytes.
        var oversized = player(TestUsers.create(profile(), new EmbeddedChannel()));
        oversized.checkManager.getCheck(ClientBrand.class).observe("x".repeat(64));
        assertEquals("sent 65 bytes as brand", oversized.getBrand());
        assertTrue(oversized.checkManager.getCheck(ClientBrand.class).isHasBrand());
    }

    private static boolean jdkValue(Object value) {
        if (value instanceof Map<?, ?> nested)
            return nested.values().stream().allMatch(ConnectionContinuationTest::jdkValue);
        return value.getClass().getClassLoader() == null;
    }

    private static User.Profile profile() {
        return new User.Profile(PLAYER, "Continued");
    }

    private static CultPlayer player(User user) {
        var player = new CultPlayer(user);
        TestUsers.attach(user, player);
        return player;
    }
}
