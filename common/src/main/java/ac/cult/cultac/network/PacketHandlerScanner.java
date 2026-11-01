package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolRuntime;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PacketHandlerScanner {
    /** Listener, event, player, packet. */
    private static final MethodType DISPATCH_TYPE =
            MethodType.methodType(void.class, Object.class, Object.class, CultPlayer.class, Object.class);

    private static final MethodType INVOKER_FACTORY_TYPE = MethodType.methodType(Invoker.class);

    private final ProtocolRuntime runtime;
    private final ClassValue<Schema> schemas = new ClassValue<>() {
        @Override
        protected Schema computeValue(Class<?> type) {
            return buildSchema(type);
        }
    };

    public PacketHandlerScanner(ProtocolRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    public ProtocolRuntime runtime() {
        return runtime;
    }

    Schema schema(Class<?> type) {
        return schemas.get(type);
    }

    public boolean supports(PacketType<?> type) {
        if (!runtime.contains(type))
            throw new IllegalArgumentException("Packet type is outside the runtime catalog: " + type);
        return runtime.supports(type);
    }

    private PacketType<?> packetType(Class<?> owner, Method method, PacketDirection direction) {
        Class<?> record = method.getParameterTypes()[2];
        if (!record.isRecord()) throw invalid(owner, method, "third parameter must be one concrete catalog record");
        if (method.getGenericParameterTypes()[0] instanceof ParameterizedType eventType) {
            var argument = eventType.getActualTypeArguments()[0];
            if (!argument.equals(record)
                    && !(argument instanceof WildcardType wildcard
                            && wildcard.getLowerBounds().length == 0
                            && Arrays.equals(wildcard.getUpperBounds(), new java.lang.reflect.Type[] {Object.class}))) {
                throw invalid(owner, method, "event record argument must match the third parameter");
            }
        }
        String key = method.getAnnotation(CultPacketHandler.class).value();
        PacketType<?> type = key.isEmpty() ? runtime.typeForRecord(record) : runtime.typeForKey(key);
        if (type == null) throw invalid(owner, method, "record is outside the runtime catalog: " + record.getName());
        if (type.recordClass() != record) throw invalid(owner, method, "record does not match the catalog key: " + key);
        if (type.direction() != direction) throw invalid(owner, method, "record direction does not match the event");
        return supports(type) ? type : null;
    }

    /** Validate the whole listener, then bind only its receive callbacks in declaration order. */
    public List<ReceiveRegistration> receiveHandlers(Object listener) {
        if (listener == null) return List.of();
        List<ReceiveRegistration> receives = new ArrayList<>();
        for (Entry entry : schema(listener.getClass()).entries()) {
            if (entry.direction() != PacketDirection.SERVERBOUND || entry.packetType() == null) continue;
            Invoker invoker = entry.invoker();
            Method method = entry.method();
            PacketReceiveHandler<Object> callback =
                    (event, player, packet) -> invoke(invoker, method, listener, event, player, packet);
            receives.add(new ReceiveRegistration(entry.packetType(), callback, entry.method()));
        }
        return List.copyOf(receives);
    }

    /** Validate the whole listener, then bind only its send callbacks in declaration order. */
    public List<SendRegistration> sendHandlers(Object listener) {
        if (listener == null) return List.of();
        List<SendRegistration> sends = new ArrayList<>();
        for (Entry entry : schema(listener.getClass()).entries()) {
            if (entry.direction() != PacketDirection.CLIENTBOUND || entry.packetType() == null) continue;
            Invoker invoker = entry.invoker();
            Method method = entry.method();
            PacketSendHandler<Object> callback =
                    (event, player, packet) -> invoke(invoker, method, listener, event, player, packet);
            sends.add(new SendRegistration(entry.packetType(), callback, entry.method()));
        }
        return List.copyOf(sends);
    }

    public boolean hasReceiveHandlerDeclaration(Class<?> type) {
        return schema(type).hasDeclaration(PacketDirection.SERVERBOUND);
    }

    public boolean hasSendHandlerDeclaration(Class<?> type) {
        return schema(type).hasDeclaration(PacketDirection.CLIENTBOUND);
    }

    /** Class-only discovery uses the exact schema that supplies bound callbacks. */
    public List<PacketType<?>> packetTypes(Class<?> listenerClass, PacketDirection direction) {
        if (listenerClass == null) return List.of();
        List<PacketType<?>> result = new ArrayList<>();
        for (Entry entry : schema(listenerClass).entries()) {
            if (entry.direction() == direction && entry.packetType() != null) result.add(entry.packetType());
        }
        return List.copyOf(result);
    }

    private Schema buildSchema(Class<?> listenerClass) {
        List<Entry> entries = new ArrayList<>();
        Set<PacketType<?>> routes = new HashSet<>();
        for (Method method : annotatedMethods(listenerClass)) {
            PacketDirection direction = direction(listenerClass, method);
            var packetType = packetType(listenerClass, method, direction);
            if (packetType != null && !routes.add(packetType)) {
                throw invalid(listenerClass, method, "duplicates " + direction + " handler for " + packetType);
            }
            // Keep unsupported declarations: direction checks still need them.
            entries.add(new Entry(method, direction, packetType, invoker(listenerClass, method)));
        }
        return new Schema(List.copyOf(entries));
    }

    /** {@code invoker} takes the listener as its first argument and is shared by every listener of the class. */
    record Entry(Method method, PacketDirection direction, PacketType<?> packetType, Invoker invoker) {}

    /** Listener, event, player, packet. Public because spun implementations live in the handler's package. */
    @FunctionalInterface
    public interface Invoker {
        void call(Object listener, Object event, CultPlayer player, Object packet) throws Throwable;
    }

    /** The reflective path for handlers a spun class cannot reach. */
    record HandleInvoker(MethodHandle handle) implements Invoker {
        @Override
        public void call(Object listener, Object event, CultPlayer player, Object packet) throws Throwable {
            handle.invokeExact(listener, event, player, packet);
        }
    }

    record Schema(List<Entry> entries) {
        boolean hasDeclaration(PacketDirection direction) {
            return entries.stream().anyMatch(entry -> entry.direction() == direction);
        }
    }

    private static List<Method> annotatedMethods(Class<?> listenerClass) {
        List<Method> methods = new ArrayList<>();
        Set<MethodSignature> shadowedSignatures = new HashSet<>();

        for (Class<?> current = listenerClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            Method[] declaredMethods = current.getDeclaredMethods();
            Arrays.sort(
                    declaredMethods,
                    Comparator.comparing(Method::getName).thenComparing(PacketHandlerScanner::parameterSignature));
            for (Method method : declaredMethods) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }

                MethodSignature signature = MethodSignature.from(method);
                if (!shadowedSignatures.add(signature)) {
                    continue;
                }
                if (method.isAnnotationPresent(CultPacketHandler.class)) {
                    methods.add(method);
                }
            }
        }

        for (Method method : listenerClass.getMethods()) {
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass == Object.class
                    || (!declaringClass.isInterface() && declaringClass.isAssignableFrom(listenerClass))
                    || method.isBridge()
                    || method.isSynthetic()) {
                continue;
            }

            MethodSignature signature = MethodSignature.from(method);
            if (!shadowedSignatures.add(signature)) {
                continue;
            }
            if (method.isAnnotationPresent(CultPacketHandler.class)) {
                methods.add(method);
            }
        }
        methods.sort(Comparator.comparing(
                        (Method method) -> method.getDeclaringClass().getName())
                .thenComparing(Method::getName)
                .thenComparing(PacketHandlerScanner::parameterSignature));
        return methods;
    }

    private static PacketDirection direction(Class<?> listenerClass, Method method) {
        if (Modifier.isStatic(method.getModifiers())) throw invalid(listenerClass, method, "must not be static");
        if (method.getReturnType() != void.class) throw invalid(listenerClass, method, "must return void");
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != 3)
            throw invalid(listenerClass, method, "must accept exactly event, CultPlayer, and one concrete packet");
        if (parameters[1] != CultPlayer.class)
            throw invalid(listenerClass, method, "second parameter must be CultPlayer");
        if (parameters[0] == PacketReceiveEvent.class) return PacketDirection.SERVERBOUND;
        if (parameters[0] == PacketSendEvent.class) return PacketDirection.CLIENTBOUND;
        throw invalid(listenerClass, method, "first parameter must be PacketReceiveEvent or PacketSendEvent");
    }

    private static Invoker invoker(Class<?> listenerClass, Method method) {
        MethodHandle handle = unboundHandle(listenerClass, method);
        Invoker direct = directInvoker(method);
        return direct != null ? direct : new HandleInvoker(handle);
    }

    static MethodHandle unboundHandle(Class<?> listenerClass, Method method) {
        try {
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(DISPATCH_TYPE);
        } catch (IllegalAccessException exception) {
            throw invalid(listenerClass, method, "could not create method handle", exception);
        }
    }

    /** A spun class that calls {@code method} directly, or null when the declaring class's lookup cannot host it. */
    static Invoker directInvoker(Method method) {
        try {
            var lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle target = lookup.unreflect(method);
            CallSite site = LambdaMetafactory.metafactory(
                    lookup, "call", INVOKER_FACTORY_TYPE, DISPATCH_TYPE, target, target.type());
            return (Invoker) site.getTarget().invoke();
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    private static void invoke(
            Invoker invoker, Method method, Object listener, Object event, CultPlayer player, Object packet) {
        try {
            invoker.call(listener, event, player, packet);
        } catch (Throwable failure) {
            throw invocationFailure(method, failure);
        }
    }

    private static RuntimeException invocationFailure(Method method, Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Packet handler failed: " + method, throwable);
    }

    private static IllegalStateException invalid(Class<?> listenerClass, Method method, String reason) {
        return invalid(listenerClass, method, reason, null);
    }

    private static IllegalStateException invalid(
            Class<?> listenerClass, Method method, String reason, Throwable cause) {
        return new IllegalStateException(
                "Invalid @CultPacketHandler in " + listenerClass.getName() + "#" + method.getName() + ": " + reason,
                cause);
    }

    private static String parameterSignature(Method method) {
        StringBuilder builder = new StringBuilder();
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(parameterType.getName());
        }
        return builder.toString();
    }

    public record ReceiveRegistration(PacketType<?> packetType, PacketReceiveHandler<Object> handler, Method method) {}

    public record SendRegistration(PacketType<?> packetType, PacketSendHandler<Object> handler, Method method) {}

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private static MethodSignature from(Method method) {
            return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
        }
    }
}
