package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundCustomPayload;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.latency.ClientInterpolationOptIn;
import ac.cult.cultac.utils.math.Vec3;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The client's declaration of {@link ClientInterpolationOptIn#CHANNEL}, observed from its plugin channel lists. */
class ClientInterpolationOptInTest {
    @Test
    void aDeclarationFollowsRegisterAndUnregisterInPlay() throws Exception {
        try (var f = new JavaTickFixture()) {
            var optIn = f.player.interpolationOptIn;
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "fabric:registry/sync\0antistutter:linear_3");
            assertTrue(optIn.isDeclared());
            channels(f, ConnectionPhase.PLAY, "minecraft:unregister", "antistutter:linear_3");
            assertFalse(optIn.isDeclared());
            assertTrue(f.player.pluginChannelManager.getRegisteredChannels().contains("fabric:registry/sync"));
        }
    }

    @Test
    void aConfigurationPhaseRegisterIsNoDeclaration() throws Exception {
        try (var f = new JavaTickFixture()) {
            channels(f, ConnectionPhase.CONFIGURATION, "minecraft:register", "antistutter:linear_3");
            assertFalse(f.player.interpolationOptIn.isDeclared());
        }
    }

    @Test
    void aDeclarationPastTheChannelCapCounts() throws Exception {
        try (var f = new JavaTickFixture()) {
            for (int list = 0; list < 2; list++) {
                StringBuilder many = new StringBuilder();
                for (int i = 0; i < 520; i++) many.append("test:").append(list).append('.').append(i).append('\0');
                channels(f, ConnectionPhase.PLAY, "minecraft:register", many.toString());
            }
            assertEquals(1024, f.player.pluginChannelManager.getRegisteredChannels().size());
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
            assertFalse(f.player.pluginChannelManager.getRegisteredChannels().contains("antistutter:linear_3"));
            assertTrue(f.player.interpolationOptIn.isDeclared());
        }
    }

    @Test
    void interpolationFixsOwnChannelNeverCounts() throws Exception {
        try (var f = new JavaTickFixture()) {
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "interpolationfix:linear_3");
            assertFalse(f.player.interpolationOptIn.isDeclared());
        }
    }

    @Test
    void reconfigurationResetsTheSession() throws Exception {
        try (var f = new JavaTickFixture()) {
            var optIn = f.player.interpolationOptIn;
            optIn.loginHandled(true);
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
            assertTrue(optIn.linearForNewEntities());
            f.player.checkManager.dispatchReceiveHandlers(new PacketReceiveEvent<>(
                    f.fixture.user,
                    ConnectionPhase.PLAY,
                    ServerboundPackets.CONFIGURATION_ACKNOWLEDGED,
                    ServerboundPackets.CONFIGURATION_ACKNOWLEDGED.opaqueValue()));
            assertFalse(optIn.isOffered());
            assertFalse(optIn.isDeclared());
            assertFalse(optIn.linearForNewEntities());
        }
    }

    @Test
    void theOfferIsFixedForThePlaySessionAtItsLogin() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = LinearInterpolationOfferTest.proxy(true)) {
            var optIn = f.player.interpolationOptIn;
            LinearInterpolationOfferTest.login(f);
            LinearInterpolationOfferTest.key(false);
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
            assertTrue(optIn.linearForNewEntities());
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            assertTrue(f.player.compensatedEntities.getEntity(100).usesLinearInterpolationHandler());
            LinearInterpolationOfferTest.reconfigure(f);
            LinearInterpolationOfferTest.login(f);
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3");
            assertFalse(optIn.isOffered());
            assertFalse(optIn.linearForNewEntities());
        }
    }

    @Test
    void withTheKeyOffEntitiesStaySteppedWhateverIsDeclared() throws Exception {
        try (var f = new JavaTickFixture();
                var ignored = LinearInterpolationOfferTest.proxy(false)) {
            LinearInterpolationOfferTest.login(f);
            channels(f, ConnectionPhase.PLAY, "minecraft:register", "antistutter:linear_3\0interpolationfix:linear_3");
            assertTrue(f.player.interpolationOptIn.isDeclared());
            f.player.compensatedEntities.addEntity(100, EntityTypeIds.ZOMBIE, new Vec3(0.5, 64, 0.5), 0, 0, 0);
            PacketEntity zombie = f.player.compensatedEntities.getEntity(100);
            assertFalse(zombie.usesLinearInterpolationHandler());
            assertTrue(zombie.newPacketLocation.usesSteppedInterpolation());
        }
    }

    static void channels(JavaTickFixture f, ConnectionPhase phase, String channel, String list) {
        var payload = new ServerboundCustomPayload(channel, list.getBytes(StandardCharsets.UTF_8));
        var event = new PacketReceiveEvent<>(f.fixture.user, phase, ServerboundPackets.CUSTOM_PAYLOAD, payload);
        f.player.pluginChannelManager.onCustomPayload(event, f.player, payload);
    }
}
