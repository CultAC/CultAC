package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.events.packets.listeners.PacketServerChannels;
import ac.cult.cultac.manager.config.BaseConfigManager;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.platform.api.Platform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundCustomPayload;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundLogin;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CultAC is the only source of the linear interpolation offer. */
class LinearInterpolationOfferTest {
    @Test
    void withTheKeyOnEachLoginToA263JavaClientFollowsOneOffer() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = proxy(true)) {
            assertOffer(login(f));
            assertTrue(f.player.interpolationOptIn.isOffered());
            reconfigure(f);
            assertFalse(f.player.interpolationOptIn.isOffered());
            assertOffer(login(f));
            assertTrue(f.player.interpolationOptIn.isOffered());
        }
    }

    @Test
    void noOfferWithTheKeyOffForBedrockOrForAnOlderJavaClient() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = proxy(false)) {
            assertEquals(List.of(), offers(login(f)));
            assertFalse(f.player.interpolationOptIn.isOffered());
        }
        try (var f = new JavaTickFixture();
                var ignored = proxy(true)) {
            f.player.movementPlatform = MovementPlatform.BEDROCK;
            assertEquals(List.of(), offers(login(f)));
            assertFalse(f.player.interpolationOptIn.isOffered());
        }
        try (var f = new JavaTickFixture();
                var ignored = proxy(true)) {
            JavaTickFixture.setField(CultPlayer.class, f.player, "resolvedClientVersion", ClientVersion.V_1_21_11);
            assertEquals(List.of(), offers(login(f)));
            assertFalse(f.player.interpolationOptIn.isOffered());
        }
    }

    @Test
    void aForeignRegisterLosesTheLinearChannelsAndKeepsTheRestInOrder() throws Exception {
        for (String channel : new String[] {"minecraft:register", "minecraft:unregister"}) {
            try (var f = new JavaTickFixture()) {
                var event = filtered(
                        f, channel, "velocity:a\0antistutter:linear_3\0fabric:b\0interpolationfix:linear_3\0lunar:c");
                assertFalse(event.isCancelled());
                assertEquals(channel, event.getPacket().channel());
                assertEquals(
                        "velocity:a\0fabric:b\0lunar:c",
                        new String(event.getPacket().data(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void aForeignFrameNamingOnlyTheLinearChannelIsDropped() throws Exception {
        for (String channel : new String[] {"minecraft:register", "minecraft:unregister"}) {
            try (var f = new JavaTickFixture()) {
                assertTrue(filtered(f, channel, "antistutter:linear_3").isCancelled());
                assertTrue(filtered(f, channel, "antistutter:linear_3\0").isCancelled());
            }
        }
    }

    @Test
    void otherFramesPassUntouched() throws Exception {
        try (var f = new JavaTickFixture()) {
            var register = filtered(f, "minecraft:register", "velocity:a\0fabric:b");
            assertFalse(register.isCancelled());
            assertFalse(register.isReplaced());
            var other = filtered(f, "lunar:apollo", "antistutter:linear_3");
            assertFalse(other.isCancelled());
            assertSame(other.getOriginalPacket(), other.getPacket());
        }
    }

    /** The proxy platform with the opt-in key {@code on}, until closed. */
    static AutoCloseable proxy(boolean on) throws Exception {
        Platform platform = CultAPI.INSTANCE.getPlatform();
        BaseConfigManager config = CultAPI.INSTANCE.getConfigManager();
        boolean key = config.isLinearInterpolationOptIn();
        JavaTickFixture.setField(CultAPI.class, CultAPI.INSTANCE, "platform", Platform.VELOCITY);
        key(on);
        return () -> {
            JavaTickFixture.setField(CultAPI.class, CultAPI.INSTANCE, "platform", platform);
            key(key);
        };
    }

    static void key(boolean on) throws Exception {
        JavaTickFixture.setField(
                BaseConfigManager.class, CultAPI.INSTANCE.getConfigManager(), "linearInterpolationOptIn", on);
    }

    /** A LOGIN sent and handled by the client; the packets written before it. */
    static List<CultWrite> login(JavaTickFixture f) {
        var login = new ClientboundLogin(7, false, new PlayerSpawnInfo(0, "minecraft:overworld", GameMode.SURVIVAL));
        var event = new PacketSendEvent<>(f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.LOGIN, login, false);
        new PacketPlayerRespawn().onLogin(event, f.player, login);
        List<CultWrite> before = List.copyOf(event.getWritesBeforeSend());
        event.getTasksAfterSend().forEach(Runnable::run);
        f.player.sendTransaction();
        for (int id = f.player.lastTransactionReceived.get() + 1; id <= f.player.lastTransactionSent.get(); id++) {
            f.player.lastTransactionReceived.set(id);
            f.player.latencyUtils.handleNettySyncTransaction(id);
        }
        return before;
    }

    /** The client acknowledges a reconfiguration. */
    static void reconfigure(JavaTickFixture f) {
        f.player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                f.fixture.user,
                ConnectionPhase.PLAY,
                ServerboundPackets.CONFIGURATION_ACKNOWLEDGED,
                ServerboundPackets.CONFIGURATION_ACKNOWLEDGED.opaqueValue()));
    }

    private static List<CultWrite> offers(List<CultWrite> writes) {
        return writes.stream()
                .filter(write -> write.packet() instanceof ClientboundCustomPayload payload
                        && payload.channel().equals("minecraft:register"))
                .toList();
    }

    private static void assertOffer(List<CultWrite> writes) {
        List<CultWrite> offers = offers(writes);
        assertEquals(1, offers.size());
        assertTrue(offers.get(0).silent());
        var payload = (ClientboundCustomPayload) offers.get(0).packet();
        assertEquals("antistutter:linear_3", new String(payload.data(), StandardCharsets.UTF_8));
    }

    private static PacketSendEvent<ClientboundCustomPayload> filtered(JavaTickFixture f, String channel, String list) {
        var payload = new ClientboundCustomPayload(channel, list.getBytes(StandardCharsets.UTF_8));
        var event = new PacketSendEvent<>(
                f.fixture.user, ConnectionPhase.PLAY, ClientboundPackets.CUSTOM_PAYLOAD, payload, false);
        PacketServerChannels.onCustomPayload(event, payload);
        return event;
    }
}
