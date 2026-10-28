package ac.cult.cultac.network;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.resources.Identifier;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public final class CultNetworkManagerProtocolStateTest {
    @Test
    public void sharedPacketsKeepTheirCurrentConfigurationOrPlayPhase() throws Exception {
        List<Packet<?>> packets = List.of(
                new ClientboundPingPacket(7),
                new ServerboundPongPacket(7),
                new ClientboundKeepAlivePacket(8L),
                new ServerboundKeepAlivePacket(8L),
                new ServerboundResourcePackPacket(new UUID(0L, 1L), ServerboundResourcePackPacket.Action.ACCEPTED),
                new ServerboundCookieResponsePacket(Identifier.parse("cult:test"), null)
        );
        for (ConnectionProtocol phase : List.of(ConnectionProtocol.CONFIGURATION, ConnectionProtocol.PLAY)) {
            for (Packet<?> packet : packets) {
                assertEquals(packet.getClass().getSimpleName(), phase, resolve(packet, phase));
            }
        }
    }

    @Test
    public void configurationKeepAliveDoesNotMisclassifyTheFollowingBrandPayload() throws Exception {
        ConnectionProtocol phase = resolve(new ServerboundKeepAlivePacket(8L), ConnectionProtocol.CONFIGURATION);
        phase = resolve(new ServerboundCustomPayloadPacket(new BrandPayload("vanilla")), phase);
        assertEquals(ConnectionProtocol.CONFIGURATION, phase);
    }

    @Test
    public void cookieResponseKeepsTheLoginPhase() throws Exception {
        assertEquals(ConnectionProtocol.LOGIN, resolve(
                new ServerboundCookieResponsePacket(Identifier.parse("cult:test"), null), ConnectionProtocol.LOGIN));
    }

    @Test
    public void phaseSpecificPacketsSelectTheirRegisteredProtocol() throws Exception {
        assertEquals(ConnectionProtocol.LOGIN,
                resolve(new ServerboundHelloPacket("Review", new UUID(0L, 1L)), ConnectionProtocol.HANDSHAKING));
        assertEquals(ConnectionProtocol.STATUS,
                resolve(ServerboundStatusRequestPacket.INSTANCE, ConnectionProtocol.HANDSHAKING));
        assertEquals(ConnectionProtocol.CONFIGURATION,
                resolve(new ServerboundSelectKnownPacks(List.of()), ConnectionProtocol.LOGIN));
    }

    @Test
    public void explicitTransitionsStillChangePhase() throws Exception {
        assertEquals(ConnectionProtocol.CONFIGURATION,
                resolve(ClientboundStartConfigurationPacket.INSTANCE, ConnectionProtocol.PLAY));
        assertEquals(ConnectionProtocol.CONFIGURATION,
                resolve(ServerboundConfigurationAcknowledgedPacket.INSTANCE, ConnectionProtocol.PLAY));
        assertEquals(ConnectionProtocol.CONFIGURATION,
                resolve(ClientboundFinishConfigurationPacket.INSTANCE, ConnectionProtocol.CONFIGURATION));
        assertEquals(ConnectionProtocol.PLAY,
                resolve(ServerboundFinishConfigurationPacket.INSTANCE, ConnectionProtocol.CONFIGURATION));
    }

    private static ConnectionProtocol resolve(Packet<?> packet, ConnectionProtocol phase) throws Exception {
        Method resolve = CultNetworkManager.class.getDeclaredMethod(
                "resolveConnectionState", Packet.class, ConnectionProtocol.class);
        resolve.setAccessible(true);
        return (ConnectionProtocol) resolve.invoke(new CultNetworkManager(), packet, phase);
    }
}
