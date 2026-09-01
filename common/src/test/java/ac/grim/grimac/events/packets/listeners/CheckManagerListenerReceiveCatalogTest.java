package ac.grim.grimac.events.packets.listeners;

import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.configuration.ConfigurationPacketTypes;
import net.minecraft.network.protocol.cookie.CookiePacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakePacketTypes;
import net.minecraft.network.protocol.login.LoginPacketTypes;
import net.minecraft.network.protocol.ping.PingPacketTypes;
import net.minecraft.network.protocol.status.StatusPacketTypes;
import net.minecraft.server.Bootstrap;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public final class CheckManagerListenerReceiveCatalogTest {
    private static final List<Class<?>> PACKET_TYPE_HOLDERS = List.of(
            CommonPacketTypes.class,
            ConfigurationPacketTypes.class,
            CookiePacketTypes.class,
            GamePacketTypes.class,
            HandshakePacketTypes.class,
            LoginPacketTypes.class,
            PingPacketTypes.class,
            StatusPacketTypes.class
    );

    @Test
    public void decodedPlayCatalogIncludesEveryServerboundPlayPacketClass() throws IllegalAccessException {
        List<Class<? extends Packet<?>>> packetTypes = CheckManagerListener.receiveDispatchPacketTypes();

        assertEquals(decodedServerboundPlayPacketClasses(), new HashSet<>(packetTypes));
        assertEquals(packetTypes.size(), new HashSet<>(packetTypes).size());
    }

    @SuppressWarnings("unchecked")
    private static Set<Class<? extends Packet<?>>> decodedServerboundPlayPacketClasses()
            throws IllegalAccessException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Set<PacketType<?>> protocolPacketTypes = new HashSet<>();
        GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets(
                (packetType, packetId) -> protocolPacketTypes.add(packetType)
        );

        Set<Class<? extends Packet<?>>> packetClasses = new HashSet<>();
        for (Class<?> holder : PACKET_TYPE_HOLDERS) {
            for (Field field : holder.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !PacketType.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                PacketType<?> packetType = (PacketType<?>) field.get(null);
                if (!protocolPacketTypes.contains(packetType)) {
                    continue;
                }

                Type genericType = field.getGenericType();
                if (!(genericType instanceof ParameterizedType parameterizedType)) {
                    continue;
                }
                Type packetClass = parameterizedType.getActualTypeArguments()[0];
                if (packetClass instanceof Class<?> concreteClass
                        && Packet.class.isAssignableFrom(concreteClass)) {
                    packetClasses.add((Class<? extends Packet<?>>) concreteClass);
                }
            }
        }

        assertEquals(protocolPacketTypes.size(), packetClasses.size());
        return packetClasses;
    }
}
