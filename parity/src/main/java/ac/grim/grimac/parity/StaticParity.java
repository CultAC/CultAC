package ac.grim.grimac.parity;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Bytecode-level semantic comparison for a pair of runtime checks.
 *
 * <p>Debug metadata, source line numbers, compiler-local labels, and known
 * transport/package representations are normalized. Constants, branch
 * ordering, field accesses, method calls, mutable field sets, and reachable
 * in-repository dependencies remain in the signature. Consequently a changed
 * threshold, flag call, cancellation branch, packet mutation, or state field
 * cannot be hidden by the transport equivalence rules.</p>
 */
public final class StaticParity {
    private static final String APPLICATION_PREFIX = "ac/grim/grimac/";
    private static final String PACKET_EVENTS_PREFIX = "com/github/retrooper/packetevents/";
    private static final String SHADED_PACKET_EVENTS_PREFIX =
            "ac/grim/grimac/shaded/com/github/retrooper/packetevents/";
    private static final String NMS_PACKET_PREFIX = "net/minecraft/network/protocol/";
    private static final String NMS_PACKET_ROOT = "net/minecraft/network/";
    private static final String BUKKIT_PREFIX = "org/bukkit/";

    private StaticParity() {
    }

    public record Result(
            String stableKey,
            String baselineClass,
            String currentClass,
            boolean equivalent,
            String baselineDigest,
            String currentDigest,
            List<String> differences,
            List<String> baselineDependencies,
            List<String> currentDependencies,
            String firstDifference
    ) {
        public Result {
            differences = List.copyOf(differences);
            baselineDependencies = List.copyOf(baselineDependencies);
            currentDependencies = List.copyOf(currentDependencies);
            firstDifference = firstDifference == null ? "" : firstDifference;
        }
    }

    public record ClassSignature(
            String normalizedClass,
            List<String> fields,
            List<String> methods,
            List<String> instructions,
            String digest
    ) {
        public ClassSignature {
            fields = List.copyOf(fields);
            methods = List.copyOf(methods);
            instructions = List.copyOf(instructions);
        }
    }

    public static Result compare(
            String stableKey,
            String baselineClass,
            String currentClass,
            Path baselineJar,
            Path currentJar,
            Map<String, CheckInventory.ClassMapping> reviewedMappings
    ) throws IOException {
        Objects.requireNonNull(stableKey, "stableKey");
        try (JarClasses baseline = new JarClasses(baselineJar);
             JarClasses current = new JarClasses(currentJar)) {
            byte[] baselineBytes = baseline.classBytes(baselineClass);
            byte[] currentBytes = current.classBytes(currentClass);
            List<String> differences = new ArrayList<>();
            if (baselineBytes == null) {
                differences.add("baseline class is absent from jar: " + baselineClass);
            }
            if (currentBytes == null) {
                differences.add("current class is absent from jar: " + currentClass);
            }
            if (!differences.isEmpty()) {
                return new Result(
                        stableKey, baselineClass, currentClass, false, "", "", differences,
                        List.of(), List.of(), differences.get(0)
                );
            }

            Equivalence equivalence = new Equivalence(reviewedMappings);
            ClassSignature left = signature(baselineBytes, "baseline", equivalence);
            ClassSignature right = signature(currentBytes, "current", equivalence);
            if (!left.digest().equals(right.digest())) {
                differences.addAll(firstSignatureDifferences(left, right));
            }

            Set<String> baselineDependencies = reachableApplicationClasses(baseline, baselineClass);
            Set<String> currentDependencies = reachableApplicationClasses(current, currentClass);
            Map<String, String> baselineDependencyDigests = dependencyDigests(
                    baseline, baselineDependencies, "baseline", equivalence
            );
            Map<String, String> currentDependencyDigests = dependencyDigests(
                    current, currentDependencies, "current", equivalence
            );
            Set<String> dependencyNames = new LinkedHashSet<>();
            dependencyNames.addAll(baselineDependencyDigests.keySet());
            dependencyNames.addAll(currentDependencyDigests.keySet());
            for (String dependency : dependencyNames.stream().sorted().toList()) {
                String leftDigest = baselineDependencyDigests.get(dependency);
                String rightDigest = currentDependencyDigests.get(dependency);
                if (!Objects.equals(leftDigest, rightDigest)) {
                    differences.add("transitive dependency differs: " + dependency
                            + " baseline=" + valueOrMissing(leftDigest)
                            + " current=" + valueOrMissing(rightDigest));
                }
            }

            String first = differences.isEmpty() ? "" : differences.get(0);
            return new Result(
                    stableKey,
                    baselineClass,
                    currentClass,
                    differences.isEmpty(),
                    left.digest(),
                    right.digest(),
                    List.copyOf(new LinkedHashSet<>(differences)),
                    baselineDependencies.stream().sorted().toList(),
                    currentDependencies.stream().sorted().toList(),
                    first
            );
        }
    }

    public static ClassSignature signature(byte[] classBytes, String side, Equivalence equivalence) {
        ClassNode node = read(classBytes);
        String normalizedClass = equivalence.className(node.name, side);
        List<String> fields = node.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_SYNTHETIC) == 0)
                .map(field -> field.access + " " + field.name + " "
                        + equivalence.descriptor(field.desc, side) + " " + constant(field.value, equivalence, side))
                .sorted()
                .toList();

        List<String> methods = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        List<MethodNode> sortedMethods = node.methods.stream()
                .filter(method -> !method.name.equals("<clinit>"))
                .sorted(Comparator.comparing((MethodNode method) -> method.name)
                        .thenComparing(method -> method.desc))
                .toList();
        for (MethodNode method : sortedMethods) {
            String methodHeader = method.access + " " + method.name + " "
                    + equivalence.descriptor(method.desc, side);
            methods.add(methodHeader);
            instructions.add("METHOD " + methodHeader);
            appendInstructions(method.instructions, instructions, equivalence, side);
        }
        String canonical = normalizedClass + "\nfields\n" + String.join("\n", fields)
                + "\nmethods\n" + String.join("\n", methods)
                + "\ninstructions\n" + String.join("\n", instructions);
        return new ClassSignature(
                normalizedClass,
                fields,
                methods,
                instructions,
                sha256(canonical)
        );
    }

    public static List<String> firstSignatureDifferences(ClassSignature left, ClassSignature right) {
        List<String> differences = new ArrayList<>();
        firstListDifference("field", left.fields(), right.fields(), differences);
        firstListDifference("method", left.methods(), right.methods(), differences);
        firstListDifference("instruction", left.instructions(), right.instructions(), differences);
        if (differences.isEmpty() && !left.normalizedClass().equals(right.normalizedClass())) {
            differences.add("class identity differs: " + left.normalizedClass() + " vs " + right.normalizedClass());
        }
        return List.copyOf(differences);
    }

    private static void firstListDifference(
            String kind,
            List<String> left,
            List<String> right,
            List<String> differences
    ) {
        int limit = Math.max(left.size(), right.size());
        for (int index = 0; index < limit; index++) {
            String leftValue = index < left.size() ? left.get(index) : "<missing>";
            String rightValue = index < right.size() ? right.get(index) : "<missing>";
            if (!leftValue.equals(rightValue)) {
                differences.add(kind + " difference at index " + index
                        + ": baseline=" + leftValue + " current=" + rightValue);
                if (differences.size() >= 8) {
                    return;
                }
            }
        }
    }

    private static void appendInstructions(
            org.objectweb.asm.tree.InsnList list,
            List<String> output,
            Equivalence equivalence,
            String side
    ) {
        IdentityHashMap<LabelNode, Integer> labels = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode instruction : list) {
            if (instruction instanceof LabelNode label) {
                labels.put(label, index);
            } else if (!(instruction instanceof LineNumberNode)) {
                index++;
            }
        }

        index = 0;
        for (AbstractInsnNode instruction : list) {
            if (instruction instanceof LabelNode || instruction instanceof LineNumberNode) {
                continue;
            }
            output.add(index + ":" + instructionToken(instruction, labels, equivalence, side));
            index++;
        }
    }

    private static String instructionToken(
            AbstractInsnNode instruction,
            IdentityHashMap<LabelNode, Integer> labels,
            Equivalence equivalence,
            String side
    ) {
        int opcode = instruction.getOpcode();
        String prefix = opcode + ":" + opcodeName(opcode);
        if (instruction instanceof VarInsnNode variable) {
            return prefix + ":var=" + variable.var;
        }
        if (instruction instanceof IntInsnNode integer) {
            return prefix + ":operand=" + integer.operand;
        }
        if (instruction instanceof IincInsnNode increment) {
            return prefix + ":var=" + increment.var + ":increment=" + increment.incr;
        }
        if (instruction instanceof TypeInsnNode type) {
            return prefix + ":type=" + equivalence.className(type.desc, side);
        }
        if (instruction instanceof FieldInsnNode field) {
            return prefix + ":owner=" + equivalence.className(field.owner, side)
                    + ":name=" + field.name + ":desc=" + equivalence.descriptor(field.desc, side);
        }
        if (instruction instanceof MethodInsnNode method) {
            return prefix + ":owner=" + equivalence.className(method.owner, side)
                    + ":name=" + method.name + ":desc=" + equivalence.descriptor(method.desc, side);
        }
        if (instruction instanceof InvokeDynamicInsnNode dynamic) {
            return prefix + ":name=" + dynamic.name + ":desc="
                    + equivalence.descriptor(dynamic.desc, side)
                    + ":bootstrap=" + handle(dynamic.bsm, equivalence, side);
        }
        if (instruction instanceof LdcInsnNode ldc) {
            return prefix + ":constant=" + constant(ldc.cst, equivalence, side);
        }
        if (instruction instanceof JumpInsnNode jump) {
            return prefix + ":target=" + labels.getOrDefault(jump.label, -1);
        }
        if (instruction instanceof LookupSwitchInsnNode lookup) {
            List<String> targets = lookup.labels.stream()
                    .map(label -> String.valueOf(labels.getOrDefault(label, -1))).toList();
            return prefix + ":keys=" + lookup.keys + ":targets=" + targets
                    + ":default=" + labels.getOrDefault(lookup.dflt, -1);
        }
        if (instruction instanceof TableSwitchInsnNode table) {
            List<String> targets = table.labels.stream()
                    .map(label -> String.valueOf(labels.getOrDefault(label, -1))).toList();
            return prefix + ":min=" + table.min + ":max=" + table.max + ":targets=" + targets
                    + ":default=" + labels.getOrDefault(table.dflt, -1);
        }
        if (instruction instanceof MultiANewArrayInsnNode array) {
            return prefix + ":desc=" + equivalence.descriptor(array.desc, side) + ":dims=" + array.dims;
        }
        return prefix;
    }

    private static String opcodeName(int opcode) {
        if (opcode < 0 || opcode >= OPCODE_NAMES.length || OPCODE_NAMES[opcode] == null) {
            return "LABEL_OR_UNKNOWN";
        }
        return OPCODE_NAMES[opcode];
    }

    private static String constant(Object value, Equivalence equivalence, String side) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Type type) {
            return "Type(" + equivalence.descriptor(type.getDescriptor(), side) + ")";
        }
        if (value instanceof Handle handle) {
            return handle(handle, equivalence, side);
        }
        if (value instanceof String string) {
            return "String(" + string + ")";
        }
        return value.getClass().getName() + "(" + value + ")";
    }

    private static String handle(Handle handle, Equivalence equivalence, String side) {
        return handle.getTag() + ":" + equivalence.className(handle.getOwner(), side)
                + ":" + handle.getName() + ":" + equivalence.descriptor(handle.getDesc(), side);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static Set<String> reachableApplicationClasses(JarClasses jar, String root) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && visited.size() < 2000) {
            String current = pending.remove();
            if (!visited.add(current)) {
                continue;
            }
            byte[] bytes = jar.classBytes(current);
            if (bytes == null) {
                continue;
            }
            ClassNode node = read(bytes);
            enqueue(node.superName, pending);
            node.interfaces.forEach(value -> enqueue(value, pending));
            for (MethodNode method : node.methods) {
                enqueueDescriptor(method.desc, pending);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof TypeInsnNode type) enqueue(type.desc, pending);
                    if (instruction instanceof FieldInsnNode field) {
                        enqueue(field.owner, pending);
                        enqueueDescriptor(field.desc, pending);
                    }
                    if (instruction instanceof MethodInsnNode invoke) {
                        enqueue(invoke.owner, pending);
                        enqueueDescriptor(invoke.desc, pending);
                    }
                    if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        enqueueDescriptor(dynamic.desc, pending);
                    }
                }
            }
            node.fields.forEach(field -> enqueueDescriptor(field.desc, pending));
        }
        return visited;
    }

    private static void enqueue(String name, Queue<String> pending) {
        if (name != null && name.startsWith(APPLICATION_PREFIX)) {
            pending.add(name);
        }
    }

    private static void enqueueDescriptor(String descriptor, Queue<String> pending) {
        if (descriptor == null) {
            return;
        }
        Type type;
        try {
            if (descriptor.startsWith("(")) {
                type = Type.getMethodType(descriptor);
                for (Type argument : type.getArgumentTypes()) enqueueType(argument, pending);
                enqueueType(type.getReturnType(), pending);
            } else {
                enqueueType(Type.getType(descriptor), pending);
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed external descriptor is not an application dependency.
        }
    }

    private static void enqueueType(Type type, Queue<String> pending) {
        if (type.getSort() == Type.ARRAY) {
            enqueueType(type.getElementType(), pending);
        } else if (type.getSort() == Type.OBJECT) {
            enqueue(type.getInternalName(), pending);
        }
    }

    private static Map<String, String> dependencyDigests(
            JarClasses jar,
            Collection<String> classes,
            String side,
            Equivalence equivalence
    ) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String className : classes) {
            byte[] bytes = jar.classBytes(className);
            if (bytes != null) {
                ClassSignature signature = signature(bytes, side, equivalence);
                result.put(signature.normalizedClass(), signature.digest());
            }
        }
        return result;
    }

    private static String valueOrMissing(String value) {
        return value == null ? "<missing>" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static final class Equivalence {
        private final Map<String, String> baselineToPair = new HashMap<>();
        private final Map<String, String> currentToPair = new HashMap<>();

        public Equivalence(Map<String, CheckInventory.ClassMapping> mappings) {
            for (Map.Entry<String, CheckInventory.ClassMapping> entry : mappings.entrySet()) {
                String pair = "<shared:" + entry.getKey() + ">";
                baselineToPair.put(toInternal(entry.getValue().baselineClass()), pair);
                currentToPair.put(toInternal(entry.getValue().currentClass()), pair);
            }
        }

        public String className(String internalName, String side) {
            if (internalName == null) {
                return "null";
            }
            Map<String, String> pairs = side.equals("baseline") ? baselineToPair : currentToPair;
            String exact = pairs.get(internalName);
            if (exact != null) {
                return exact;
            }
            if (internalName.startsWith(SHADED_PACKET_EVENTS_PREFIX)) {
                return "<packet-events>" + internalName.substring(SHADED_PACKET_EVENTS_PREFIX.length());
            }
            if (internalName.startsWith(PACKET_EVENTS_PREFIX)) {
                return "<packet-events>" + internalName.substring(PACKET_EVENTS_PREFIX.length());
            }
            if (internalName.startsWith(NMS_PACKET_PREFIX)) {
                return "<nms-packet>" + internalName.substring(NMS_PACKET_PREFIX.length());
            }
            if (internalName.startsWith(NMS_PACKET_ROOT)) {
                return "<nms-network>" + internalName.substring(NMS_PACKET_ROOT.length());
            }
            if (internalName.startsWith(BUKKIT_PREFIX)) {
                return "<platform>" + internalName.substring(BUKKIT_PREFIX.length());
            }
            if (internalName.startsWith(APPLICATION_PREFIX)) {
                return internalName;
            }
            return internalName;
        }

        public String descriptor(String descriptor, String side) {
            if (descriptor == null) {
                return "null";
            }
            StringBuilder result = new StringBuilder(descriptor.length());
            for (int index = 0; index < descriptor.length(); index++) {
                char character = descriptor.charAt(index);
                if (character != 'L') {
                    result.append(character);
                    continue;
                }
                int end = descriptor.indexOf(';', index);
                if (end < 0) {
                    result.append(descriptor.substring(index));
                    break;
                }
                result.append('L').append(className(descriptor.substring(index + 1, end), side)).append(';');
                index = end;
            }
            return result.toString();
        }

        private static String toInternal(String className) {
            return className.replace('.', '/');
        }
    }

    private static final class JarClasses implements AutoCloseable {
        private final JarFile jar;

        private JarClasses(Path path) throws IOException {
            this.jar = new JarFile(path.toFile());
        }

        private byte[] classBytes(String className) throws IOException {
            if (className == null) {
                return null;
            }
            var entry = jar.getJarEntry(className.replace('.', '/') + ".class");
            if (entry == null) {
                entry = jar.getJarEntry(className + ".class");
            }
            return entry == null ? null : jar.getInputStream(entry).readAllBytes();
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    private static final String[] OPCODE_NAMES = opcodeNames();

    private static String[] opcodeNames() {
        String[] names = new String[256];
        names[Opcodes.NOP] = "NOP";
        names[Opcodes.ACONST_NULL] = "ACONST_NULL";
        names[Opcodes.ICONST_M1] = "ICONST_M1";
        names[Opcodes.ICONST_0] = "ICONST_0";
        names[Opcodes.ICONST_1] = "ICONST_1";
        names[Opcodes.ICONST_2] = "ICONST_2";
        names[Opcodes.ICONST_3] = "ICONST_3";
        names[Opcodes.ICONST_4] = "ICONST_4";
        names[Opcodes.ICONST_5] = "ICONST_5";
        names[Opcodes.LCONST_0] = "LCONST_0";
        names[Opcodes.LCONST_1] = "LCONST_1";
        names[Opcodes.FCONST_0] = "FCONST_0";
        names[Opcodes.FCONST_1] = "FCONST_1";
        names[Opcodes.FCONST_2] = "FCONST_2";
        names[Opcodes.DCONST_0] = "DCONST_0";
        names[Opcodes.DCONST_1] = "DCONST_1";
        names[Opcodes.BIPUSH] = "BIPUSH";
        names[Opcodes.SIPUSH] = "SIPUSH";
        names[Opcodes.LDC] = "LDC";
        names[Opcodes.ILOAD] = "ILOAD";
        names[Opcodes.LLOAD] = "LLOAD";
        names[Opcodes.FLOAD] = "FLOAD";
        names[Opcodes.DLOAD] = "DLOAD";
        names[Opcodes.ALOAD] = "ALOAD";
        names[Opcodes.IALOAD] = "IALOAD";
        names[Opcodes.LALOAD] = "LALOAD";
        names[Opcodes.FALOAD] = "FALOAD";
        names[Opcodes.DALOAD] = "DALOAD";
        names[Opcodes.AALOAD] = "AALOAD";
        names[Opcodes.BALOAD] = "BALOAD";
        names[Opcodes.CALOAD] = "CALOAD";
        names[Opcodes.SALOAD] = "SALOAD";
        names[Opcodes.IASTORE] = "IASTORE";
        names[Opcodes.LASTORE] = "LASTORE";
        names[Opcodes.FASTORE] = "FASTORE";
        names[Opcodes.DASTORE] = "DASTORE";
        names[Opcodes.AASTORE] = "AASTORE";
        names[Opcodes.BASTORE] = "BASTORE";
        names[Opcodes.CASTORE] = "CASTORE";
        names[Opcodes.SASTORE] = "SASTORE";
        names[Opcodes.POP] = "POP";
        names[Opcodes.POP2] = "POP2";
        names[Opcodes.DUP] = "DUP";
        names[Opcodes.DUP_X1] = "DUP_X1";
        names[Opcodes.DUP_X2] = "DUP_X2";
        names[Opcodes.DUP2] = "DUP2";
        names[Opcodes.DUP2_X1] = "DUP2_X1";
        names[Opcodes.DUP2_X2] = "DUP2_X2";
        names[Opcodes.SWAP] = "SWAP";
        names[Opcodes.IADD] = "IADD";
        names[Opcodes.LADD] = "LADD";
        names[Opcodes.FADD] = "FADD";
        names[Opcodes.DADD] = "DADD";
        names[Opcodes.ISUB] = "ISUB";
        names[Opcodes.LSUB] = "LSUB";
        names[Opcodes.FSUB] = "FSUB";
        names[Opcodes.DSUB] = "DSUB";
        names[Opcodes.IMUL] = "IMUL";
        names[Opcodes.LMUL] = "LMUL";
        names[Opcodes.FMUL] = "FMUL";
        names[Opcodes.DMUL] = "DMUL";
        names[Opcodes.IDIV] = "IDIV";
        names[Opcodes.LDIV] = "LDIV";
        names[Opcodes.FDIV] = "FDIV";
        names[Opcodes.DDIV] = "DDIV";
        names[Opcodes.IREM] = "IREM";
        names[Opcodes.LREM] = "LREM";
        names[Opcodes.FREM] = "FREM";
        names[Opcodes.DREM] = "DREM";
        names[Opcodes.INEG] = "INEG";
        names[Opcodes.LNEG] = "LNEG";
        names[Opcodes.FNEG] = "FNEG";
        names[Opcodes.DNEG] = "DNEG";
        names[Opcodes.ISHL] = "ISHL";
        names[Opcodes.LSHL] = "LSHL";
        names[Opcodes.ISHR] = "ISHR";
        names[Opcodes.LSHR] = "LSHR";
        names[Opcodes.IUSHR] = "IUSHR";
        names[Opcodes.LUSHR] = "LUSHR";
        names[Opcodes.IAND] = "IAND";
        names[Opcodes.LAND] = "LAND";
        names[Opcodes.IOR] = "IOR";
        names[Opcodes.LOR] = "LOR";
        names[Opcodes.IXOR] = "IXOR";
        names[Opcodes.LXOR] = "LXOR";
        names[Opcodes.IINC] = "IINC";
        names[Opcodes.I2L] = "I2L";
        names[Opcodes.I2F] = "I2F";
        names[Opcodes.I2D] = "I2D";
        names[Opcodes.L2I] = "L2I";
        names[Opcodes.L2F] = "L2F";
        names[Opcodes.L2D] = "L2D";
        names[Opcodes.F2I] = "F2I";
        names[Opcodes.F2L] = "F2L";
        names[Opcodes.F2D] = "F2D";
        names[Opcodes.D2I] = "D2I";
        names[Opcodes.D2L] = "D2L";
        names[Opcodes.D2F] = "D2F";
        names[Opcodes.I2B] = "I2B";
        names[Opcodes.I2C] = "I2C";
        names[Opcodes.I2S] = "I2S";
        names[Opcodes.LCMP] = "LCMP";
        names[Opcodes.FCMPL] = "FCMPL";
        names[Opcodes.FCMPG] = "FCMPG";
        names[Opcodes.DCMPL] = "DCMPL";
        names[Opcodes.DCMPG] = "DCMPG";
        names[Opcodes.IFEQ] = "IFEQ";
        names[Opcodes.IFNE] = "IFNE";
        names[Opcodes.IFLT] = "IFLT";
        names[Opcodes.IFGE] = "IFGE";
        names[Opcodes.IFGT] = "IFGT";
        names[Opcodes.IFLE] = "IFLE";
        names[Opcodes.IF_ICMPEQ] = "IF_ICMPEQ";
        names[Opcodes.IF_ICMPNE] = "IF_ICMPNE";
        names[Opcodes.IF_ICMPLT] = "IF_ICMPLT";
        names[Opcodes.IF_ICMPGE] = "IF_ICMPGE";
        names[Opcodes.IF_ICMPGT] = "IF_ICMPGT";
        names[Opcodes.IF_ICMPLE] = "IF_ICMPLE";
        names[Opcodes.IF_ACMPEQ] = "IF_ACMPEQ";
        names[Opcodes.IF_ACMPNE] = "IF_ACMPNE";
        names[Opcodes.GOTO] = "GOTO";
        names[Opcodes.JSR] = "JSR";
        names[Opcodes.RET] = "RET";
        names[Opcodes.TABLESWITCH] = "TABLESWITCH";
        names[Opcodes.LOOKUPSWITCH] = "LOOKUPSWITCH";
        names[Opcodes.IRETURN] = "IRETURN";
        names[Opcodes.LRETURN] = "LRETURN";
        names[Opcodes.FRETURN] = "FRETURN";
        names[Opcodes.DRETURN] = "DRETURN";
        names[Opcodes.ARETURN] = "ARETURN";
        names[Opcodes.RETURN] = "RETURN";
        names[Opcodes.GETSTATIC] = "GETSTATIC";
        names[Opcodes.PUTSTATIC] = "PUTSTATIC";
        names[Opcodes.GETFIELD] = "GETFIELD";
        names[Opcodes.PUTFIELD] = "PUTFIELD";
        names[Opcodes.INVOKEVIRTUAL] = "INVOKEVIRTUAL";
        names[Opcodes.INVOKESPECIAL] = "INVOKESPECIAL";
        names[Opcodes.INVOKESTATIC] = "INVOKESTATIC";
        names[Opcodes.INVOKEINTERFACE] = "INVOKEINTERFACE";
        names[Opcodes.INVOKEDYNAMIC] = "INVOKEDYNAMIC";
        names[Opcodes.NEW] = "NEW";
        names[Opcodes.NEWARRAY] = "NEWARRAY";
        names[Opcodes.ANEWARRAY] = "ANEWARRAY";
        names[Opcodes.ARRAYLENGTH] = "ARRAYLENGTH";
        names[Opcodes.ATHROW] = "ATHROW";
        names[Opcodes.CHECKCAST] = "CHECKCAST";
        names[Opcodes.INSTANCEOF] = "INSTANCEOF";
        names[Opcodes.MONITORENTER] = "MONITORENTER";
        names[Opcodes.MONITOREXIT] = "MONITOREXIT";
        names[Opcodes.MULTIANEWARRAY] = "MULTIANEWARRAY";
        names[Opcodes.IFNULL] = "IFNULL";
        names[Opcodes.IFNONNULL] = "IFNONNULL";
        return names;
    }
}
