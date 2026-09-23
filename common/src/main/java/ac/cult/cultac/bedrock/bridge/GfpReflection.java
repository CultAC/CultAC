package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.UpstreamSession;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;

/** The only class that knows GFP Build 5's private implementation names. No GFP dependency is shaded. */
final class GfpReflection {
    private final Class<?> upstreamType;
    private final Class<?> adapterType;
    private final Field upstreamUser;
    private final Field adapterUser;
    private final Field delegates;
    private final Method userSession;
    private final Method offset;

    GfpReflection(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> user = loader.loadClass("oxy.geyser.fp.session.GeyserFPUser");
        upstreamType = loader.loadClass("oxy.geyser.fp.network.wrapper.UpstreamHandlerWrapper");
        adapterType = loader.loadClass("oxy.geyser.fp.network.wrapper.GeyserFPAdapterWrapper");
        upstreamUser = field(upstreamType, "user");
        adapterUser = field(adapterType, "user");
        delegates = field(adapterType, "listeners");
        userSession = user.getMethod("session");
        offset = user.getMethod("offset");
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

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
