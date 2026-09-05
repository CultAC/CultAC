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

    public static List<ReceiveRegistration> receiveHandlers(Object listener) {
        return scan(listener, Direction.RECEIVE).receiveRegistrations();
    }

    public static List<SendRegistration> sendHandlers(Object listener) {
        return scan(listener, Direction.SEND).sendRegistrations();
    }

    public static boolean hasReceiveHandlerDeclaration(Class<?> listenerClass) {
        return hasHandlerDeclaration(listenerClass, Direction.RECEIVE);
    }

    public static boolean hasSendHandlerDeclaration(Class<?> listenerClass) {
        return hasHandlerDeclaration(listenerClass, Direction.SEND);
    }

    public static List<Class<? extends Packet<?>>> sendPacketTypes(Class<?> listenerClass) {
        return packetTypes(listenerClass, Direction.SEND);
    }

    public static List<Class<? extends Packet<?>>> receivePacketTypes(Class<?> listenerClass) {
        return packetTypes(listenerClass, Direction.RECEIVE);
    }

    public static RegistrationSet handlers(Object listener) {
        return scan(listener, Direction.BOTH);
    }

    private static boolean hasHandlerDeclaration(Class<?> listenerClass, Direction direction) {
        for (Method method : annotatedMethods(listenerClass)) {
            if (direction.accepts(signature(method, listenerClass).direction())) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<? extends Packet<?>>> packetTypes(Class<?> listenerClass, Direction requestedDirection) {
        if (listenerClass == null) {
            return List.of();
        }

        List<Class<? extends Packet<?>>> packetTypes = new ArrayList<>();
        Set<RouteKey> routes = new HashSet<>();

        for (Method method : annotatedMethods(listenerClass)) {
            HandlerSignature signature = signature(method, listenerClass);
            if (!requestedDirection.accepts(signature.direction())) {
                continue;
            }
            for (Class<? extends Packet<?>> packetType : signature.packetTypes()) {
                RouteKey route = new RouteKey(signature.direction(), packetType);
                if (!routes.add(route)) {
                    throw invalid(listenerClass, method, "duplicates " + signature.direction().description()
                            + " handler for " + packetType.getName());
                }
                packetTypes.add(packetType);
            }
        }

        return List.copyOf(packetTypes);
    }

    private static RegistrationSet scan(Object listener, Direction requestedDirection) {
        if (listener == null) {
            return new RegistrationSet(List.of(), List.of());
        }

        List<ReceiveRegistration> receiveRegistrations = new ArrayList<>();
        List<SendRegistration> sendRegistrations = new ArrayList<>();
        Set<RouteKey> routes = new HashSet<>();

        for (Method method : annotatedMethods(listener.getClass())) {
            HandlerSignature signature = signature(method, listener.getClass());
            if (!requestedDirection.accepts(signature.direction())) {
                continue;
            }
            MethodHandle handle = handle(listener, method);
            if (signature.direction() == Direction.RECEIVE) {
                PacketReceiveHandler<Packet<?>> handler = receiveInvoker(handle, method);
                for (Class<? extends Packet<?>> packetType : signature.packetTypes()) {
                    RouteKey route = new RouteKey(signature.direction(), packetType);
                    if (!routes.add(route)) {
                        throw invalid(listener.getClass(), method, "duplicates " + signature.direction().description()
                                + " handler for " + packetType.getName());
                    }
                    receiveRegistrations.add(new ReceiveRegistration(packetType, handler));
                }
            } else {
                PacketSendHandler<Packet<?>> handler = sendInvoker(handle, method);
                for (Class<? extends Packet<?>> packetType : signature.packetTypes()) {
                    RouteKey route = new RouteKey(signature.direction(), packetType);
                    if (!routes.add(route)) {
                        throw invalid(listener.getClass(), method, "duplicates " + signature.direction().description()
                                + " handler for " + packetType.getName());
                    }
                    sendRegistrations.add(new SendRegistration(packetType, handler));
                }
            }
        }

        return new RegistrationSet(List.copyOf(receiveRegistrations), List.copyOf(sendRegistrations));
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

    private static MethodHandle handle(Object listener, Method method) {
        try {
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).bindTo(listener);
        } catch (IllegalAccessException exception) {
            throw invalid(listener.getClass(), method, "could not create method handle", exception);
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
