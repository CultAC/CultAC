package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.UpstreamSession;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.packet.Packet;

/** The only class that knows GFP Build 5's private implementation names. No GFP dependency is shaded. */
final class GfpReflection {
    private final Class<?> upstreamType;
    private final Class<?> adapterType;
    private final Field upstreamUser;
    private final Field adapterUser;
    private final Field delegates;
    private final Method userSession;
    private final Method offset;
    private final Constructor<?> event;
    private final Method getPacket;
    private final Method cancelled;
    private final Method onSend;
    private final Method onReceived;
    private final Object registry;
    private final Method listeners;

    GfpReflection(Extension extension) throws ReflectiveOperationException {
        ClassLoader loader = extension.getClass().getClassLoader();
        Class<?> user = loader.loadClass("oxy.geyser.fp.session.GeyserFPUser");
        upstreamType = loader.loadClass("oxy.geyser.fp.network.wrapper.UpstreamHandlerWrapper");
        adapterType = loader.loadClass("oxy.geyser.fp.network.wrapper.GeyserFPAdapterWrapper");
        upstreamUser = field(upstreamType, "user");
        adapterUser = field(adapterType, "user");
        delegates = field(adapterType, "listeners");
        userSession = user.getMethod("session");
        offset = user.getMethod("offset");
        Class<?> eventType = loader.loadClass("oxy.geyser.fp.network.event.JavaPacketEvent");
        event = eventType.getConstructor(Packet.class);
        getPacket = eventType.getMethod("getPacket");
        cancelled = eventType.getMethod("isCancelled");
        Class<?> listener = loader.loadClass("oxy.geyser.fp.network.listener.JavaPacketListener");
        onSend = listener.getMethod("onSend", user, eventType);
        onReceived = listener.getMethod("onReceived", user, eventType);
        Class<?> registryType = loader.loadClass("oxy.geyser.fp.network.PacketListenerRegistry");
        registry = registryType.getMethod("instance").invoke(null);
        listeners = registryType.getMethod("javaListeners");
    }

    Object user(UpstreamSession upstream, GeyserSession session) throws ReflectiveOperationException {
        if (!upstreamType.isInstance(upstream)) throw new IllegalStateException("Missing GFP upstream wrapper");
        Object user = upstreamUser.get(upstream);
        if (userSession.invoke(user) != session) throw new IllegalStateException("GFP session ownership mismatch");
        return user;
    }

    boolean isAdapter(SessionListener listener) { return adapterType.isInstance(listener); }

    SessionListener adapter(Object user, List<SessionListener> delegates) throws ReflectiveOperationException {
        return (SessionListener) adapterType.getConstructor(upstreamUser.getType(), List.class).newInstance(user, delegates);
    }

    @SuppressWarnings("unchecked")
    List<SessionListener> delegates(SessionListener adapter, Object user) throws ReflectiveOperationException {
        if (adapterUser.get(adapter) != user) throw new IllegalStateException("GFP adapter ownership mismatch");
        return List.copyOf((List<SessionListener>) delegates.get(adapter));
    }

    Vector3i offset(Object user) throws ReflectiveOperationException {
        Vector3i value = (Vector3i) offset.invoke(user);
        if (value.getY() != 0) throw new IllegalStateException("Unsupported GFP Y origin");
        return value;
    }

    Rewrite rewrite(Object user, Packet packet, boolean sending) throws ReflectiveOperationException {
        Object packetEvent = event.newInstance(packet);
        // Invoke the installed extension's real rewriters, in its registry order, including after cancellation.
        for (Object listener : (List<?>) listeners.invoke(registry)) {
            (sending ? onSend : onReceived).invoke(listener, user, packetEvent);
        }
        return new Rewrite((Packet) getPacket.invoke(packetEvent), (boolean) cancelled.invoke(packetEvent));
    }

    Rewrite sending(Object user, PacketSendingEvent event, List<SessionListener> delegates) throws ReflectiveOperationException {
        Rewrite result = rewrite(user, event.getPacket(), true);
        event.setPacket(result.packet());
        if (result.cancelled()) event.setCancelled(true);
        else delegates.forEach(listener -> listener.packetSending(event));
        return result;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    record Rewrite(Packet packet, boolean cancelled) { }
}
