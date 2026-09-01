package ac.grim.grimac.parity.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * ASM entry point for the live differential harness.
 *
 * <p>The transformed runtime only references {@link GrimParityBootstrap}.
 * That class is copied into a tiny bootstrap jar so the instrumented Grim
 * classes can resolve the callback even when Paper gives the plugin its own
 * class loader. The agent itself, including ASM, remains outside the baseline
 * and current plugin jars.</p>
 */
public final class GrimParityAgent {
    private static final String CHECK_BASE = "ac/grim/grimac/checks/Check";
    private static final String BOOTSTRAP_CLASS =
            "ac/grim/grimac/parity/agent/GrimParityBootstrap";
    private static final Type BOOTSTRAP_TYPE = Type.getObjectType(BOOTSTRAP_CLASS);
    private static final Set<String> BASE_TRACE_METHODS = Set.of(
            "flag",
            "flagWithSetback",
            "recordFlag",
            "reward",
            "reload",
            "onReload",
            "alert",
            "shouldModifyPackets",
            "setbackIfAboveSetbackVL",
            "setbackIfAboveSetbackVLNonSimulating",
            "shouldSetback",
            "executeViolationSetback"
    );

    private GrimParityAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        configure(arguments);
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(createBootstrapJar().toFile()));
        openJavaReflection(instrumentation);
        GrimParityBootstrap.agentStarted();
        instrumentation.addTransformer(new CheckTransformer(), false);
    }

    private static void openJavaReflection(Instrumentation instrumentation) {
        try {
            Class<?> bootstrap = Class.forName(
                    BOOTSTRAP_CLASS.replace('/', '.'), false, null
            );
            instrumentation.redefineModule(
                    Object.class.getModule(),
                    Set.of(),
                    Map.of(),
                    Map.of("java.lang", Set.of(bootstrap.getModule())),
                    Set.of(),
                    Map.of()
            );
        } catch (Throwable ignored) {
            // The live wrapper also supplies --add-opens where supported; the
            // class-file field fallback remains available if neither path is
            // permitted by the hosting JVM.
        }
    }

    private static void configure(String arguments) {
        if (arguments != null && !arguments.isBlank()) {
            for (String token : arguments.split(";")) {
                int equals = token.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = token.substring(0, equals).trim();
                String value = token.substring(equals + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    System.setProperty("grim.parity." + key, value);
                }
            }
        }
        copyEnvironment("GRIM_PARITY_INVENTORY", "grim.parity.inventory");
        copyEnvironment("GRIM_PARITY_TRACE", "grim.parity.trace");
        copyEnvironment("GRIM_PARITY_CHECK", "grim.parity.traceStableKey");
        copyEnvironment("GRIM_PARITY_OPTIONAL_STUBS", "grim.parity.optionalStubs");
    }

    private static void copyEnvironment(String environmentName, String propertyName) {
        if (System.getProperty(propertyName, "").isBlank()) {
            String value = System.getenv(environmentName);
            if (value != null && !value.isBlank()) {
                System.setProperty(propertyName, value);
            }
        }
    }

    private static Path createBootstrapJar() throws IOException {
        Path jar = Files.createTempFile("grim-parity-bootstrap-", ".jar");
        jar.toFile().deleteOnExit();
        List<String> entries = new ArrayList<>(List.of(BOOTSTRAP_CLASS + ".class"));
        if (Boolean.getBoolean("grim.parity.optionalStubs")) {
            entries.add("com/viaversion/viaversion/api/protocol/packet/PacketTracker.class");
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entryName : entries) {
                try (InputStream input = GrimParityAgent.class.getClassLoader().getResourceAsStream(entryName)) {
                    if (input == null) {
                        throw new IOException("Missing bootstrap resource: " + entryName);
                    }
                    output.putNextEntry(new JarEntry(entryName));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    private static final class CheckTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(
                ClassLoader loader,
                String name,
                Class<?> type,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer
        ) {
            try {
                if (name == null || classfileBuffer == null) {
                    return null;
                }

                boolean manager = name.equals("ac/grim/grimac/manager/CheckManager")
                        || name.equals("ac/grim/grimac/manager/player/CheckManager");
                if (!manager && !name.startsWith("ac/grim/grimac/")) {
                    return null;
                }
                boolean check = isCheckHierarchy(loader, name, classfileBuffer, 0);
                if (!manager && !check) {
                    return null;
                }
                String classFilter = System.getProperty("grim.parity.classFilter", "").trim();
                if (!classFilter.isEmpty() && !matchesClassFilter(name, classFilter)) {
                    return null;
                }
                GrimParityBootstrap.transformObserved(name, manager ? "manager" : "check");
                if ("false".equalsIgnoreCase(System.getProperty("grim.parity.transform", "true"))) {
                    return null;
                }
                String traceMode = System.getProperty("grim.parity.methodTrace", "full").trim().toLowerCase();
                boolean traceMethods = !traceMode.equals("false") && !traceMode.equals("none");
                boolean traceExit = traceMode.equals("full");
                ClassReader reader = new ClassReader(classfileBuffer);
                int writerFlags = traceExit ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
                ClassWriter writer = new ClassWriter(reader, writerFlags) {
                    @Override
                    protected String getCommonSuperClass(String first, String second) {
                        // Never load plugin classes while the JVM is in a
                        // class-file transform callback. Object is a valid
                        // conservative merge type and avoids recursive
                        // transformation/deadlock through Paper's loader.
                        return "java/lang/Object";
                    }
                };
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String method,
                            String descriptor,
                            String signature,
                            String[] exceptions
                    ) {
                        MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                                || method.equals("<clinit>")) {
                            return delegate;
                        }

                        if (check && method.equals("<init>") && name.equals(CHECK_BASE)) {
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, descriptor) {
                                @Override
                                protected void onMethodExit(int opcode) {
                                    if (opcode != RETURN) {
                                        return;
                                    }
                                    loadThis();
                                    visitLdcInsn(name);
                                    invokeStatic(
                                            BOOTSTRAP_TYPE,
                                            new org.objectweb.asm.commons.Method(
                                                    "checkConstructed",
                                                    "(Ljava/lang/Object;Ljava/lang/String;)V"
                                            )
                                    );
                                }
                            };
                        }

                        if (manager && method.equals("<init>")) {
                            return new AdviceAdapter(Opcodes.ASM9, delegate, access, method, descriptor) {
                                @Override
                                protected void onMethodExit(int opcode) {
                                    if (opcode != RETURN) {
                                        return;
                                    }
                                    loadThis();
                                    visitLdcInsn(name);
                                    invokeStatic(
                                            BOOTSTRAP_TYPE,
                                            new org.objectweb.asm.commons.Method(
                                                    "managerReady",
                                                    "(Ljava/lang/Object;Ljava/lang/String;)V"
                                            )
                                    );
                                }
                            };
                        }

                        // A subclass constructor runs before its Check
                        // superclass is initialized. The base constructor
                        // callback above records construction, while regular
                        // invocation tracing must begin only after Java's
                        // uninitialized-this phase has ended.
                        if (method.equals("<init>")) {
                            return delegate;
                        }

                        // The shared base class contains several methods with
                        // compiler-generated frame shapes that Paper/JDK 25
                        // rejects when rewritten during plugin loading. Its
                        // constructor is enough for construction inventory;
                        // concrete checks carry invocation tracing below.
                        if (!traceMethods || !check
                                || (name.equals(CHECK_BASE) && !BASE_TRACE_METHODS.contains(method))
                                || (access & Opcodes.ACC_STATIC) != 0) {
                            return delegate;
                        }

                        return new InvocationAdapter(
                                delegate,
                                access,
                                method,
                                descriptor,
                                name,
                                traceExit
                        );
                    }
                }, ClassReader.EXPAND_FRAMES);
                byte[] transformed = writer.toByteArray();
                GrimParityBootstrap.transformObserved(name, manager ? "complete-manager" : "complete-check");
                return transformed;
            } catch (Throwable failure) {
                GrimParityBootstrap.transformObserved(
                        name,
                        "transform-failed:" + failure.getClass().getName()
                                + ":" + String.valueOf(failure.getMessage())
                );
                return null;
            }
        }

        private static boolean matchesClassFilter(String name, String filter) {
            for (String candidate : filter.split(",")) {
                if (name.equals(candidate.trim())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isCheckHierarchy(
                ClassLoader loader,
                String name,
                byte[] bytes,
                int depth
        ) {
            if (CHECK_BASE.equals(name)) {
                return true;
            }
            if (depth > 12 || name.equals("java/lang/Object")) {
                return false;
            }

            String superName;
            try {
                superName = name.equals(new ClassReader(bytes).getClassName())
                        ? new ClassReader(bytes).getSuperName()
                        : null;
            } catch (Throwable ignored) {
                return false;
            }
            if (superName == null) {
                return false;
            }
            if (CHECK_BASE.equals(superName)) {
                return true;
            }

            ClassLoader effectiveLoader = loader == null ? ClassLoader.getSystemClassLoader() : loader;
            String resource = superName + ".class";
            try (InputStream input = effectiveLoader.getResourceAsStream(resource)) {
                if (input == null) {
                    return false;
                }
                return isCheckHierarchy(effectiveLoader, superName, input.readAllBytes(), depth + 1);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class InvocationAdapter extends AdviceAdapter {
        private final String className;
        private final String method;
        private final String descriptor;
        private final boolean traceExit;

        private InvocationAdapter(
                MethodVisitor delegate,
                int access,
                String method,
                String descriptor,
                String className,
                boolean traceExit
        ) {
            super(Opcodes.ASM9, delegate, access, method, descriptor);
            this.className = className;
            this.method = method;
            this.descriptor = descriptor;
            this.traceExit = traceExit;
        }

        @Override
        protected void onMethodEnter() {
            loadThis();
            visitLdcInsn(className);
            visitLdcInsn(method);
            visitLdcInsn(descriptor);
            pushArgumentArray();
            invokeStatic(
                    BOOTSTRAP_TYPE,
                    new org.objectweb.asm.commons.Method(
                            "enter",
                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V"
                    )
            );
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (!traceExit) {
                return;
            }
            int returnLocal = newLocal(Type.getType(Object.class));
            if (opcode == RETURN) {
                visitInsn(ACONST_NULL);
                storeLocal(returnLocal);
            } else if (opcode == ATHROW) {
                dup();
                storeLocal(returnLocal);
            } else {
                Type returnType = Type.getReturnType(descriptor);
                if (returnType.getSize() == 2) {
                    dup2();
                } else {
                    dup();
                }
                box(returnType);
                storeLocal(returnLocal);
            }

            loadThis();
            visitLdcInsn(className);
            visitLdcInsn(method);
            visitLdcInsn(descriptor);
            push(opcode);
            loadLocal(returnLocal);
            pushArgumentArray();
            invokeStatic(
                    BOOTSTRAP_TYPE,
                    new org.objectweb.asm.commons.Method(
                            "exit",
                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;[Ljava/lang/Object;)V"
                    )
            );
        }

        private void pushArgumentArray() {
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);
            push(argumentTypes.length);
            newArray(Type.getType(Object.class));
            for (int index = 0; index < argumentTypes.length; index++) {
                dup();
                push(index);
                loadArg(index);
                box(argumentTypes[index]);
                arrayStore(Type.getType(Object.class));
            }
        }
    }
}
