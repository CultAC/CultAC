package ac.cult.cultac.platform.velocity.bridge;

import ac.cult.cultac.bridge.protocol.BridgeProtocol;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Predicate;

/** A Velocity backend connection; writes bypass the channel registrar so they stay in order. */
final class VelocityBackend implements BridgeBackend {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(BridgeProtocol.CHANNEL);

    private final ServerConnection server;
    private final Channel client;
    private final Predicate<ServerConnection> inPlay;

    VelocityBackend(ServerConnection server, Channel client, Predicate<ServerConnection> inPlay) {
        this.server = server;
        this.client = client;
        this.inPlay = inPlay;
    }

    @Override
    public EventLoop eventLoop() {
        return client.eventLoop();
    }

    @Override
    public boolean inPlay() {
        return inPlay.test(server);
    }

    @Override
    public void write(byte[] frame) {
        server.sendPluginMessage(CHANNEL, frame);
    }

    @Override
    public String serverName() {
        return server.getServerInfo().getName();
    }

    /** Reads {@code VelocityServerConnection.getConnection().getState()}; Velocity has no public phase API. */
    static Predicate<ServerConnection> playStateProbe(ClassLoader proxyLoader) throws ReflectiveOperationException {
        Class<?> serverConnection = Class.forName(
                "com.velocitypowered.proxy.connection.backend.VelocityServerConnection", false, proxyLoader);
        Class<?> minecraftConnection =
                serverConnection.getMethod("getConnection").getReturnType();
        Class<?> stateType = minecraftConnection.getMethod("getState").getReturnType();
        var lookup = MethodHandles.publicLookup();
        MethodHandle connection = lookup.findVirtual(
                        serverConnection, "getConnection", MethodType.methodType(minecraftConnection))
                .asType(MethodType.methodType(Object.class, Object.class));
        MethodHandle state = lookup.findVirtual(minecraftConnection, "getState", MethodType.methodType(stateType))
                .asType(MethodType.methodType(Object.class, Object.class));
        Object play = playConstant(stateType);
        return server -> {
            try {
                Object minecraft = (Object) connection.invokeExact((Object) server);
                return minecraft != null && (Object) state.invokeExact(minecraft) == play;
            } catch (Throwable failure) {
                if (failure instanceof Error error) throw error;
                return false;
            }
        };
    }

    private static Object playConstant(Class<?> stateType) throws NoSuchFieldException {
        Object[] constants = stateType.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals("PLAY")) return constant;
            }
        }
        throw new NoSuchFieldException(stateType.getName() + ".PLAY");
    }
}
