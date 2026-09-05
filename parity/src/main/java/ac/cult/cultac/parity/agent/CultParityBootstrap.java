package ac.cult.cultac.parity.agent;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Bootstrap-safe callback used by the transformed Cult classes.
 *
 * <p>Keep this class dependency-free. It is copied into the bootstrap loader
 * by {@link CultParityAgent}; plugin classes and ASM are intentionally not
 * referenced here.</p>
 */
public final class CultParityBootstrap {
    private static final Object IO_LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Object UNSAFE = findUnsafe();
    private static final Method RAW_DECLARED_FIELDS = findRawDeclaredFields();
    private static final Set<String> NORMALIZED_FIELD_NAMES = Set.of(
            "lastViolationTime",
            "timeJoined",
            "lastJoinedWorld",
            "creationTime",
            "lastActivityTime",
            "timestamp",
            "thread",
            "threadId",
            "connectionId",
            "classLoader",
            "port"
    );

    private CultParityBootstrap() {
    }

    public static void agentStarted() {
        try {
            writeInventory(record("agent-started", "pid", String.valueOf(ProcessHandle.current().pid())));
            // A valid scenario may legitimately exercise no check callback
            // (for example, a control action that only proves the client-side
            // gate).  Materialise the JSONL stream up front so the runner can
            // distinguish an empty trace from a failed agent.
            touchConfiguredPath("cult.parity.trace");
        } catch (LinkageError | RuntimeException ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    public static void transformObserved(String className, String kind) {
        try {
            writeInventory(record("transform-observed", "class", className, "kind", kind));
        } catch (LinkageError | RuntimeException ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    public static void checkConstructed(Object check, String className) {
        try {
            if (check == null) {
                return;
            }
            String stableKey = stringProperty(check, "getStableKey", "stableKey");
            String checkName = stringProperty(check, "getCheckName", "checkName");
            writeInventory(record(
                    "check-constructed",
                    "class", className,
                    "runtimeClass", check.getClass().getName(),
                    "stableKey", stableKey,
                    "checkName", checkName
            ));
        } catch (LinkageError | RuntimeException ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    public static void managerReady(Object manager, String managerClass) {
        try {
            managerReadyInternal(manager, managerClass);
        } catch (LinkageError | RuntimeException ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    private static void managerReadyInternal(Object manager, String managerClass) {
        if (manager == null) {
            return;
        }

        Object checks = invokeNoArg(manager, "getAllChecks");
        if (checks == null) {
            checks = fieldValue(manager, "allChecks");
        }
        if (checks == null) {
            checks = fieldValue(manager, "checks");
        }

        List<String> entries = new ArrayList<>();
        if (checks instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                try {
                    if (value != null && isCheck(value)) {
                        entries.add(checkJson(value));
                    }
                } catch (LinkageError | RuntimeException ignored) {
                    // One optional check must not hide the rest of the inventory.
                }
            }
        } else if (checks instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                try {
                    if (value != null && isCheck(value)) {
                        entries.add(checkJson(value));
                    }
                } catch (LinkageError | RuntimeException ignored) {
                    // One optional check must not hide the rest of the inventory.
                }
            }
        }
        entries.sort(String::compareTo);

        StringBuilder line = new StringBuilder();
        line.append('{');
        appendString(line, "kind", "runtime-manager");
        line.append(',');
        appendString(line, "managerClass", managerClass);
        line.append(",\"entries\":[");
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(entries.get(index));
        }
        line.append("]}");
        writeInventory(line.toString());
    }

    public static void enter(
            Object self,
            String className,
            String method,
            String descriptor,
            Object[] arguments
    ) {
        try {
            if (!isCheck(self) || !isTraceTarget(self)) {
                return;
            }
            StringBuilder line = baseInvocation("enter", self, className, method, descriptor);
            line.append(",\"arguments\":").append(snapshot(arguments, 5));
            line.append(",\"stateBefore\":").append(snapshot(self, 7));
            line.append('}');
            writeTrace(line.toString());
        } catch (Throwable ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    public static void exit(
            Object self,
            String className,
            String method,
            String descriptor,
            int opcode,
            Object returnValue,
            Object[] arguments
    ) {
        try {
            if (!isCheck(self) || !isTraceTarget(self)) {
                return;
            }
            StringBuilder line = baseInvocation("exit", self, className, method, descriptor);
            line.append(",\"opcode\":").append(opcode);
            line.append(",\"returnValue\":").append(snapshot(returnValue, 3));
            line.append(",\"argumentsAfter\":").append(snapshot(arguments, 5));
            String decision = decisionKind(className, method);
            if (!decision.isEmpty()) {
                line.append(',');
                appendString(line, "decision", decision);
            }
            line.append(",\"stateAfter\":").append(snapshot(self, 7));
            line.append('}');
            writeTrace(line.toString());
        } catch (LinkageError | RuntimeException ignored) {
            // Instrumentation is observational and must never affect the check.
        }
    }

    private static boolean isTraceTarget(Object self) {
        String target = System.getProperty("cult.parity.traceStableKey", "").trim();
        return target.isEmpty() || target.equals(stringProperty(self, "getStableKey", "stableKey"));
    }

    private static String decisionKind(String className, String method) {
        if (!className.equals("ac/cult/cultac/checks/Check")
                && !className.equals("ac/grim/grimac/checks/Check")) {
            return "";
        }
        return switch (method) {
            case "flag", "flagWithSetback", "recordFlag", "reward", "alert",
                    "shouldModifyPackets", "setbackIfAboveSetbackVL",
                    "setbackIfAboveSetbackVLNonSimulating", "shouldSetback",
                    "executeViolationSetback" -> method;
            default -> "";
        };
    }

    private static boolean isCheck(Object value) {
        Class<?> type = value.getClass();
        for (int depth = 0; type != null && depth < 16; depth++, type = type.getSuperclass()) {
            if ("ac.cult.cultac.checks.Check".equals(type.getName())
                    || "ac.grim.grimac.checks.Check".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static StringBuilder baseInvocation(
            String phase,
            Object self,
            String className,
            String method,
            String descriptor
    ) {
        StringBuilder line = new StringBuilder();
        line.append('{');
        appendString(line, "kind", "invoke");
        line.append(',');
        appendString(line, "phase", phase);
        line.append(',');
        line.append("\"sequence\":").append(SEQUENCE.incrementAndGet());
        line.append(',');
        appendString(line, "checkClass", className);
        line.append(',');
        appendString(line, "runtimeClass", self.getClass().getName());
        line.append(',');
        appendString(line, "stableKey", stringProperty(self, "getStableKey", "stableKey"));
        line.append(',');
        appendString(line, "method", method);
        line.append(',');
        appendString(line, "descriptor", descriptor);
        line.append(',');
        line.append("\"logicalPosition\":").append(logicalPosition(self));
        return line;
    }

    private static String checkJson(Object check) {
        StringBuilder line = new StringBuilder();
        line.append('{');
        appendString(line, "class", check.getClass().getName());
        line.append(',');
        appendString(line, "stableKey", stringProperty(check, "getStableKey", "stableKey"));
        line.append(',');
        appendString(line, "checkName", stringProperty(check, "getCheckName", "checkName"));
        line.append(',');
        appendString(line, "configName", stringProperty(check, "getConfigName", "configName"));
        line.append(',');
        line.append(jsonKey("enabled")).append(':')
                .append(booleanProperty(check, "isEnabled", "isEnabled"));
        line.append('}');
        return line.toString();
    }

    private static String logicalPosition(Object check) {
        Object player = fieldValue(check, "player");
        if (player == null) {
            return "null";
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : List.of(
                "transactionID", "lastTransactionSent", "lastTransactionReceived",
                "totalFlyingPacketsSent", "ticksSincePlayerMove", "x", "y", "z"
        )) {
            Object value = fieldValue(player, field);
            if (value != null) {
                values.put(field, value);
            }
        }
        return snapshot(values, 3);
    }

    private static String snapshot(Object value, int maxDepth) {
        return snapshotValue(value, 0, maxDepth, new IdentityHashMap<>(), new int[]{0}, "root");
    }

    private static String snapshotValue(
            Object value,
            int depth,
            int maxDepth,
            IdentityHashMap<Object, Integer> seen,
            int[] objectCount,
            String fieldName
    ) {
        if (value == null) {
            return "null";
        }
        if (isNormalized(fieldName)) {
            return "\"<normalized:" + escape(fieldName) + ">\"";
        }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>
                || value instanceof Number || value instanceof Boolean) {
            return scalarJson(value);
        }
        if (value instanceof Class<?> type) {
            return stringJson("<class:" + type.getName() + ">");
        }
        if (value instanceof java.util.UUID uuid) {
            return stringJson(uuid.toString());
        }
        if (depth >= maxDepth || objectCount[0]++ > 8000) {
            return stringJson("<depth:" + value.getClass().getName() + ">");
        }

        Integer objectId = seen.get(value);
        if (objectId != null) {
            return "{\"$ref\":" + objectId + "}";
        }
        int newId = seen.size() + 1;
        seen.put(value, newId);

        Class<?> type = value.getClass();
        if (type.isArray()) {
            StringBuilder result = new StringBuilder("[");
            int length = Math.min(Array.getLength(value), 256);
            for (int index = 0; index < length; index++) {
                if (index > 0) {
                    result.append(',');
                }
                result.append(snapshotValue(Array.get(value, index), depth + 1, maxDepth, seen, objectCount, fieldName));
            }
            if (Array.getLength(value) > length) {
                result.append(',').append(stringJson("<truncated-array>"));
            }
            return result.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            StringBuilder result = new StringBuilder("{");
            appendString(result, "$id", String.valueOf(newId));
            int count = 0;
            for (Map.Entry<?, ?> entry : entries) {
                if (count++ >= 256) {
                    break;
                }
                result.append(',');
                result.append(jsonKey(String.valueOf(entry.getKey()))).append(':')
                        .append(snapshotValue(entry.getValue(), depth + 1, maxDepth, seen, objectCount, fieldName));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder("[");
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= 256) {
                    result.append(',').append(stringJson("<truncated-collection>"));
                    break;
                }
                if (count > 1) {
                    result.append(',');
                }
                result.append(snapshotValue(item, depth + 1, maxDepth, seen, objectCount, fieldName));
            }
            return result.append(']').toString();
        }

        StringBuilder result = new StringBuilder("{");
        appendString(result, "$id", String.valueOf(newId));
        List<Field> fields = fields(type);
        for (Field field : fields) {
            String name = field.getName();
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            Object fieldValue;
            try {
                fieldValue = readField(value, field);
            } catch (Throwable ignored) {
                continue;
            }
            result.append(',').append(jsonKey(name)).append(':')
                    .append(snapshotValue(fieldValue, depth + 1, maxDepth, seen, objectCount, name));
        }
        return result.append('}').toString();
    }

    private static Object readField(Object target, Field field) throws Throwable {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable primary) {
            if (UNSAFE == null) {
                throw primary;
            }
            Class<?> unsafeType = UNSAFE.getClass();
            long offset = ((Number) unsafeType.getMethod("objectFieldOffset", Field.class)
                    .invoke(UNSAFE, field)).longValue();
            Class<?> fieldType = field.getType();
            String name = fieldType.getName();
            if (!fieldType.isPrimitive()) {
                return unsafeType.getMethod("getObject", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("boolean")) {
                return unsafeType.getMethod("getBoolean", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("byte")) {
                return unsafeType.getMethod("getByte", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("short")) {
                return unsafeType.getMethod("getShort", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("char")) {
                return unsafeType.getMethod("getChar", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("int")) {
                return unsafeType.getMethod("getInt", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("long")) {
                return unsafeType.getMethod("getLong", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("float")) {
                return unsafeType.getMethod("getFloat", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            if (name.equals("double")) {
                return unsafeType.getMethod("getDouble", Object.class, long.class)
                        .invoke(UNSAFE, target, offset);
            }
            throw primary;
        }
    }

    private static Object findUnsafe() {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field field = type.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findRawDeclaredFields() {
        try {
            Method method = Class.class.getDeclaredMethod("getDeclaredFields0", boolean.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(declaredFields(current));
        }
        fields.sort(Comparator.comparing(field -> field.getDeclaringClass().getName() + "#" + field.getName()));
        return fields;
    }

    private static List<Field> declaredFields(Class<?> type) {
        try {
            return Arrays.asList(type.getDeclaredFields());
        } catch (LinkageError | RuntimeException ignored) {
            // Reflection resolves every field descriptor as a group.  A
            // missing optional compile-only type can therefore hide unrelated
            // fields.  Read the class-file field names and resolve them one at
            // a time so the available state remains observable.
        }

        if (RAW_DECLARED_FIELDS != null) {
            try {
                return Arrays.asList((Field[]) RAW_DECLARED_FIELDS.invoke(type, false));
            } catch (LinkageError | ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the class-file name recovery below.
            }
        }

        List<Field> result = new ArrayList<>();
        List<String> names = declaredFieldNames(type);
        for (String name : names) {
            try {
                result.add(type.getDeclaredField(name));
            } catch (LinkageError | ReflectiveOperationException | RuntimeException ignored) {
                // The value of this one field is not loadable in this fixture.
            }
        }
        return result;
    }

    private static List<String> declaredFieldNames(Class<?> type) {
        String resourceName = type.getName().replace('.', '/') + ".class";
        InputStream resource = null;
        try {
            ClassLoader loader = type.getClassLoader();
            if (loader != null) {
                resource = loader.getResourceAsStream(resourceName);
            }
            if (resource == null) {
                resource = type.getResourceAsStream("/" + resourceName);
            }
            if (resource == null) {
                resource = type.getResourceAsStream(type.getSimpleName() + ".class");
            }
            if (resource == null) {
                return fieldNamesFromCodeSource(type, resourceName);
            }
            return parseFieldNames(resource);
        } catch (LinkageError | IOException | RuntimeException ignored) {
            return List.of();
        } finally {
            if (resource != null) {
                try {
                    resource.close();
                } catch (IOException ignored) {
                    // Best-effort diagnostic resource cleanup.
                }
            }
        }
    }

    private static List<String> fieldNamesFromCodeSource(Class<?> type, String resourceName) {
        try {
            if (type.getProtectionDomain() == null
                    || type.getProtectionDomain().getCodeSource() == null) {
                return List.of();
            }
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(location)) {
                return List.of();
            }
            try (JarFile jar = new JarFile(location.toFile())) {
                JarEntry entry = jar.getJarEntry(resourceName);
                return entry == null ? List.of() : parseFieldNames(jar.getInputStream(entry));
            }
        } catch (LinkageError | IOException | RuntimeException | URISyntaxException ignored) {
            return List.of();
        }
    }

    private static List<String> parseFieldNames(InputStream resource) throws IOException {
        try (DataInputStream input = new DataInputStream(resource)) {
            if (input.readInt() != 0xCAFEBABE) {
                return List.of();
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            String[] utf8 = new String[input.readUnsignedShort()];
            for (int index = 1; index < utf8.length; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> {
                        input.readLong();
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                    }
                    case 15 -> {
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown class-file constant-pool tag");
                }
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            input.readUnsignedShort();
            int interfaces = input.readUnsignedShort();
            input.skipNBytes((long) interfaces * 2L);
            int fieldCount = input.readUnsignedShort();
            List<String> names = new ArrayList<>(fieldCount);
            for (int index = 0; index < fieldCount; index++) {
                input.readUnsignedShort();
                int nameIndex = input.readUnsignedShort();
                input.readUnsignedShort();
                int attributes = input.readUnsignedShort();
                if (nameIndex > 0 && nameIndex < utf8.length && utf8[nameIndex] != null) {
                    names.add(utf8[nameIndex]);
                }
                for (int attribute = 0; attribute < attributes; attribute++) {
                    input.readUnsignedShort();
                    input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
                }
            }
            return names;
        }
    }

    private static boolean isNormalized(String fieldName) {
        return NORMALIZED_FIELD_NAMES.contains(fieldName);
    }

    private static String scalarJson(Object value) {
        if (value instanceof Number number) {
            String text = String.valueOf(number);
            if (number instanceof Double || number instanceof Float) {
                if (!Double.isFinite(number.doubleValue())) {
                    return stringJson(text);
                }
            }
            return text;
        }
        return stringJson(String.valueOf(value));
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object fieldValue(Object target, String fieldName) {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue with the superclass.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringProperty(Object target, String methodName, String fieldName) {
        Object value = invokeNoArg(target, methodName);
        if (value == null) {
            value = fieldValue(target, fieldName);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanProperty(Object target, String methodName, String fieldName) {
        Object value = invokeNoArg(target, methodName);
        if (value == null) {
            value = fieldValue(target, fieldName);
        }
        return Boolean.TRUE.equals(value);
    }

    private static void writeTrace(String line) {
        writeConfiguredPath("cult.parity.trace", line);
    }

    private static void touchConfiguredPath(String property) {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            return;
        }
        synchronized (IO_LOCK) {
            try {
                Path path = Path.of(configured);
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        path,
                        "",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                );
            } catch (IOException | RuntimeException ignored) {
                // Diagnostics must never change the runtime decision under test.
            }
        }
    }

    private static void writeInventory(String line) {
        writeConfiguredPath("cult.parity.inventory", line);
    }

    private static void writeConfiguredPath(String property, String line) {
        String configured = System.getProperty(property, "");
        if (configured.isBlank()) {
            return;
        }
        write(Path.of(configured), line);
    }

    private static void write(Path path, String line) {
        if (path.toString().isBlank()) {
            return;
        }
        synchronized (IO_LOCK) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        path,
                        line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException ignored) {
                // Diagnostics must never change the runtime decision under test.
            }
        }
    }

    private static String record(String kind, String... values) {
        StringBuilder result = new StringBuilder("{");
        appendString(result, "kind", kind);
        for (int index = 0; index + 1 < values.length; index += 2) {
            result.append(',');
            appendString(result, values[index], values[index + 1]);
        }
        return result.append('}').toString();
    }

    private static void appendString(StringBuilder result, String key, String value) {
        result.append(jsonKey(key)).append(':');
        if (value == null) {
            result.append("null");
        } else {
            result.append(stringJson(value));
        }
    }

    private static String jsonKey(String key) {
        return stringJson(key);
    }

    private static String stringJson(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.toString();
    }
}
