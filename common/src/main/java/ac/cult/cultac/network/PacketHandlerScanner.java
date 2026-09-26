package ac.cult.cultac.network;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PacketHandlerScanner {
    private PacketHandlerScanner() {
    }

    private static final ClassValue<HandlerSchema> SCHEMAS = new ClassValue<>() {
        @Override
        protected HandlerSchema computeValue(Class<?> listenerClass) {
            return HandlerSchema.build(listenerClass);
        }
    };

    public static List<ReceiveRegistration> receiveHandlers(Object listener) {
        return scan(listener, Direction.RECEIVE).receiveRegistrations();
    }

    public static List<SendRegistration> sendHandlers(Object listener) {
        return scan(listener, Direction.SEND).sendRegistrations();
    }

    public static boolean hasReceiveHandlerDeclaration(Class<?> listenerClass) {
        return SCHEMAS.get(listenerClass).hasReceiveDeclaration();
    }

    public static boolean hasSendHandlerDeclaration(Class<?> listenerClass) {
        return SCHEMAS.get(listenerClass).hasSendDeclaration();
    }

    public static List<Class<? extends Packet<?>>> sendPacketTypes(Class<?> listenerClass) {
        return listenerClass == null ? List.of() : SCHEMAS.get(listenerClass).sendPacketTypes();
    }

    public static List<Class<? extends Packet<?>>> receivePacketTypes(Class<?> listenerClass) {
        return listenerClass == null ? List.of() : SCHEMAS.get(listenerClass).receivePacketTypes();
    }

    public static RegistrationSet handlers(Object listener) {
        return scan(listener, Direction.BOTH);
    }

    private static RegistrationSet scan(Object listener, Direction requestedDirection) {
        if (listener == null) {
            return new RegistrationSet(List.of(), List.of());
        }

        List<ReceiveRegistration> receiveRegistrations = new ArrayList<>();
        List<SendRegistration> sendRegistrations = new ArrayList<>();

        for (HandlerEntry entry : SCHEMAS.get(listener.getClass()).entries()) {
            if (!requestedDirection.accepts(entry.direction()) || entry.packetTypes().isEmpty()) {
                continue;
            }
            MethodHandle handle = entry.unboundHandle().bindTo(listener);
            if (entry.direction() == Direction.RECEIVE) {
                PacketReceiveHandler<Packet<?>> handler = receiveInvoker(handle, entry.method());
                for (Class<? extends Packet<?>> packetType : entry.packetTypes()) {
                    receiveRegistrations.add(new ReceiveRegistration(packetType, handler));
                }
            } else {
                PacketSendHandler<Packet<?>> handler = sendInvoker(handle, entry.method());
                for (Class<? extends Packet<?>> packetType : entry.packetTypes()) {
                    sendRegistrations.add(new SendRegistration(packetType, handler));
                }
            }
        }

        return new RegistrationSet(List.copyOf(receiveRegistrations), List.copyOf(sendRegistrations));
    }

    private record HandlerEntry(
            Method method,
            Direction direction,
            List<Class<? extends Packet<?>>> packetTypes,
            MethodHandle unboundHandle
    ) {
    }

    private record HandlerSchema(
            List<HandlerEntry> entries,
            boolean hasReceiveDeclaration,
            boolean hasSendDeclaration,
            List<Class<? extends Packet<?>>> receivePacketTypes,
            List<Class<? extends Packet<?>>> sendPacketTypes
    ) {
        private static HandlerSchema build(Class<?> listenerClass) {
            List<HandlerEntry> entries = new ArrayList<>();
            List<Class<? extends Packet<?>>> receivePacketTypes = new ArrayList<>();
            List<Class<? extends Packet<?>>> sendPacketTypes = new ArrayList<>();
            Set<RouteKey> routes = new HashSet<>();
            boolean hasReceiveDeclaration = false;
            boolean hasSendDeclaration = false;

            for (Method method : annotatedMethods(listenerClass)) {
                HandlerSignature signature = signature(method, listenerClass);
                // A declaration counts even when its optional packetClass is absent on this runtime.
                if (signature.direction() == Direction.RECEIVE) {
                    hasReceiveDeclaration = true;
                } else {
                    hasSendDeclaration = true;
                }
                List<Class<? extends Packet<?>>> packetTypes = signature.direction() == Direction.RECEIVE
                        ? receivePacketTypes : sendPacketTypes;
                for (Class<? extends Packet<?>> packetType : signature.packetTypes()) {
                    RouteKey route = new RouteKey(signature.direction(), packetType);
                    if (!routes.add(route)) {
                        throw invalid(listenerClass, method, "duplicates " + signature.direction().description()
                                + " handler for " + packetType.getName());
                    }
                    packetTypes.add(packetType);
                }
                entries.add(new HandlerEntry(method, signature.direction(), signature.packetTypes(),
                        unboundHandle(listenerClass, method)));
            }

            return new HandlerSchema(List.copyOf(entries), hasReceiveDeclaration, hasSendDeclaration,
                    List.copyOf(receivePacketTypes), List.copyOf(sendPacketTypes));
        }
    }

    private static List<Method> annotatedMethods(Class<?> listenerClass) {
        List<Method> methods = new ArrayList<>();
        Set<MethodSignature> shadowedSignatures = new HashSet<>();

        for (Class<?> current = listenerClass; current != null && current != Object.class; current = current.getSuperclass()) {
            Method[] declaredMethods = current.getDeclaredMethods();
            Arrays.sort(declaredMethods, Comparator
                    .comparing(Method::getName)
                    .thenComparing(PacketHandlerScanner::parameterSignature));
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
        methods.sort(Comparator
                .comparing((Method method) -> method.getDeclaringClass().getName())
                .thenComparing(Method::getName)
                .thenComparing(PacketHandlerScanner::parameterSignature));
        return methods;
    }

    private static HandlerSignature signature(Method method, Class<?> listenerClass) {
        if (Modifier.isStatic(method.getModifiers())) {
            throw invalid(listenerClass, method, "must not be static");
        }
        if (method.getReturnType() != Void.TYPE) {
            throw invalid(listenerClass, method, "must return void");
        }

        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 3) {
            throw invalid(listenerClass, method, "must accept exactly event, CultPlayer, and one concrete packet");
        }
        if (parameterTypes[1] != CultPlayer.class) {
            throw invalid(listenerClass, method, "second parameter must be CultPlayer");
        }

        Direction direction;
        if (parameterTypes[0] == PacketReceiveEvent.class) {
            direction = Direction.RECEIVE;
        } else if (parameterTypes[0] == PacketSendEvent.class) {
            direction = Direction.SEND;
        } else {
            throw invalid(listenerClass, method, "first parameter must be PacketReceiveEvent or PacketSendEvent");
        }

        List<Class<? extends Packet<?>>> packetTypes = packetTypes(parameterTypes[2], direction, listenerClass, method);
        return new HandlerSignature(direction, packetTypes);
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Packet<?>>> packetTypes(
            Class<?> parameterType,
            Direction direction,
            Class<?> listenerClass,
            Method method
    ) {
        CultPacketHandler handlerAnnotation = method.getAnnotation(CultPacketHandler.class);
        String optionalPacketClass = handlerAnnotation.packetClass().trim();
        if (!optionalPacketClass.isEmpty()) {
            if (parameterType != Packet.class) {
                throw invalid(listenerClass, method,
                        "handlers using packetClass must accept Packet as their third parameter");
            }
            try {
                Class<?> resolved = Class.forName(optionalPacketClass, false, listenerClass.getClassLoader());
                if (!Packet.class.isAssignableFrom(resolved)
                        || resolved.isInterface()
                        || Modifier.isAbstract(resolved.getModifiers())) {
                    throw invalid(listenerClass, method,
                            "packetClass must name one concrete Packet implementation: " + optionalPacketClass);
                }
                return List.of((Class<? extends Packet<?>>) resolved);
            } catch (ClassNotFoundException ignored) {
                return List.of();
            }
        }
        if (!Packet.class.isAssignableFrom(parameterType)) {
            throw invalid(listenerClass, method, "third parameter must extend Packet");
        }
        if (parameterType == Packet.class) {
            throw invalid(listenerClass, method, "must name one concrete packet class, not Packet");
        }

        CultPacketGroup groupAnnotation = method.getAnnotation(CultPacketGroup.class);
        if (groupAnnotation != null) {
            PacketGroup group = groupAnnotation.value();
            if (group.flow() != direction.flow()) {
                throw invalid(listenerClass, method, "packet group " + group.name()
                        + " is for " + group.flow() + " handlers, not " + direction.description());
            }
            if (parameterType != group.familyType()) {
                throw invalid(listenerClass, method, "packet group " + group.name()
                        + " requires third parameter " + group.familyType().getName());
            }
            return group.packetTypes();
        }

        if (parameterType.isInterface() || Modifier.isAbstract(parameterType.getModifiers())) {
            throw invalid(listenerClass, method, "must name one concrete packet class, not an abstract packet family");
        }
        return List.of((Class<? extends Packet<?>>) parameterType);
    }

    private static MethodHandle unboundHandle(Class<?> listenerClass, Method method) {
        try {
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException exception) {
            throw invalid(listenerClass, method, "could not create method handle", exception);
        }
    }

    private static PacketReceiveHandler<Packet<?>> receiveInvoker(MethodHandle handle, Method method) {
        return new MethodHandleReceiveHandler(method, handle);
    }

    private static PacketSendHandler<Packet<?>> sendInvoker(MethodHandle handle, Method method) {
        return new MethodHandleSendHandler(method, handle);
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

    private static IllegalStateException invalid(Class<?> listenerClass, Method method, String reason, Throwable cause) {
        return new IllegalStateException("Invalid @CultPacketHandler in "
                + listenerClass.getName() + "#" + method.getName() + ": " + reason, cause);
    }

    private static final class MethodHandleReceiveHandler implements PacketReceiveHandler<Packet<?>> {
        private final Method method;
        private final MethodHandle handle;

        private MethodHandleReceiveHandler(Method method, MethodHandle handle) {
            this.method = method;
            this.handle = handle;
        }

        @Override
        public void handle(PacketReceiveEvent event, CultPlayer player, Packet<?> packet) {
            try {
                handle.invoke(event, player, packet);
            } catch (Throwable throwable) {
                throw invocationFailure(method, throwable);
            }
        }
    }

    private static final class MethodHandleSendHandler implements PacketSendHandler<Packet<?>> {
        private final Method method;
        private final MethodHandle handle;

        private MethodHandleSendHandler(Method method, MethodHandle handle) {
            this.method = method;
            this.handle = handle;
        }

        @Override
        public void handle(PacketSendEvent event, CultPlayer player, Packet<?> packet) {
            try {
                handle.invoke(event, player, packet);
            } catch (Throwable throwable) {
                throw invocationFailure(method, throwable);
            }
        }
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

    private enum Direction {
        RECEIVE("receive", PacketFlow.SERVERBOUND),
        SEND("send", PacketFlow.CLIENTBOUND),
        BOTH("both", null);

        private final String description;
        private final PacketFlow flow;

        Direction(String description, PacketFlow flow) {
            this.description = description;
            this.flow = flow;
        }

        boolean accepts(Direction direction) {
            return this == BOTH || this == direction;
        }

        String description() {
            return description;
        }

        PacketFlow flow() {
            return flow;
        }
    }

    public record RegistrationSet(
            List<ReceiveRegistration> receiveRegistrations,
            List<SendRegistration> sendRegistrations
    ) {
    }

    public record ReceiveRegistration(
            Class<? extends Packet<?>> packetType,
            PacketReceiveHandler<Packet<?>> handler
    ) {
    }

    public record SendRegistration(
            Class<? extends Packet<?>> packetType,
            PacketSendHandler<Packet<?>> handler
    ) {
    }

    private record HandlerSignature(Direction direction, List<Class<? extends Packet<?>>> packetTypes) {
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private static MethodSignature from(Method method) {
            return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
        }
    }

    private record RouteKey(Direction direction, Class<? extends Packet<?>> packetType) {
    }
}
