package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketListenerPriority;
import net.minecraft.network.protocol.Packet;

public final class PacketRegistrar {
    private final CultNetworkManager networkManager;

    public PacketRegistrar(CultNetworkManager networkManager) {
        this.networkManager = networkManager;
    }

    public <T extends Packet<?>> void receive(Class<T> packetType, PacketListenerPriority priority,
                                              PacketReceiveHandler<? super T> handler) {
        networkManager.registerReceiveHandler(packetType, priority, handler);
    }

    public <T extends Packet<?>> void earlyReceive(Class<T> packetType, PacketListenerPriority priority,
                                                   PacketReceiveHandler<? super T> handler) {
        networkManager.registerEarlyReceiveHandler(packetType, priority, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerReceiveListener(PacketListenerPriority priority, Object listener) {
        var registrations = PacketHandlerScanner.receiveHandlers(listener);
        if (registrations.isEmpty()
                && !PacketHandlerScanner.hasReceiveHandlerDeclaration(listener.getClass())) {
            throw new IllegalStateException("No receive @CultPacketHandler methods on "
                    + listener.getClass().getName());
        }
        for (PacketHandlerScanner.ReceiveRegistration registration : registrations) {
            networkManager.registerReceiveHandler((Class) registration.packetType(), priority, registration.handler());
        }
    }

    public <T extends Packet<?>> void send(Class<T> packetType, PacketListenerPriority priority,
                                           PacketSendHandler<? super T> handler) {
        networkManager.registerSendHandler(packetType, priority, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerSendListener(PacketListenerPriority priority, Object listener) {
        var registrations = PacketHandlerScanner.sendHandlers(listener);
        if (registrations.isEmpty()
                && !PacketHandlerScanner.hasSendHandlerDeclaration(listener.getClass())) {
            throw new IllegalStateException("No send @CultPacketHandler methods on "
                    + listener.getClass().getName());
        }
        for (PacketHandlerScanner.SendRegistration registration : registrations) {
            networkManager.registerSendHandler((Class) registration.packetType(), priority, registration.handler());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerListener(PacketListenerPriority priority, Object listener) {
        PacketHandlerScanner.RegistrationSet registrations = PacketHandlerScanner.handlers(listener);
        if (registrations.receiveRegistrations().isEmpty()
                && registrations.sendRegistrations().isEmpty()
                && !PacketHandlerScanner.hasReceiveHandlerDeclaration(listener.getClass())
                && !PacketHandlerScanner.hasSendHandlerDeclaration(listener.getClass())) {
            throw new IllegalStateException("No @CultPacketHandler methods on "
                    + listener.getClass().getName());
        }
        for (PacketHandlerScanner.ReceiveRegistration registration : registrations.receiveRegistrations()) {
            networkManager.registerReceiveHandler((Class) registration.packetType(), priority, registration.handler());
        }
        for (PacketHandlerScanner.SendRegistration registration : registrations.sendRegistrations()) {
            networkManager.registerSendHandler((Class) registration.packetType(), priority, registration.handler());
        }
    }
}
