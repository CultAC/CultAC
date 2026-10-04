package ac.cult.placement;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Shrinks classes and guards shared caches as the isolated loader defines them.
 *
 * <ul>
 *   <li>Non-capturing lambda sites bootstrap through {@code SharedLambdas} and share one class
 *       per interface instead of one per site.</li>
 *   <li>Non-capturing method references to another class ({@code Zombie::new}) name their
 *       target symbolically and resolve it with the caller's lookup on first call. Linking
 *       loads (never initializes) the target, so registry bootstraps no longer load hundreds
 *       of entity, AI and feature classes the client model never calls.</li>
 *   <li>Local variable and parameter tables are dropped; line numbers stay for stack traces.
 *       A fresh constant pool keeps only constants still referenced.</li>
 * </ul>
 */
final class VanillaClassTransform {
    static final String RUNTIME = "ac/cult/placement/runtime/";
    static final String SHARED_LAMBDAS = RUNTIME + "SharedLambdas";
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String LAZY_DESCRIPTOR = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;ILjava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;";

    private VanillaClassTransform() {}

    /** {@code narrowed} also applies {@link ModelNarrowing}. */
    static byte[] apply(String name, byte[] bytes, boolean narrowed) {
        String internalName = name.replace('.', '/');
        if (internalName.equals(SHARED_LAMBDAS)) return bytes;
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor shrink = new ClassVisitor(Opcodes.ASM9, writer) {
            private String owner;

            @Override
            public void visit(
                    int version, int access, String name, String signature, String superName, String[] interfaces) {
                owner = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitSource(String source, String debug) {
                super.visitSource(source, null);
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(
                        Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitLocalVariable(String n, String d, String s, Label start, Label end, int index) {}

                    @Override
                    public void visitParameter(String n, int access) {}

                    @Override
                    public void visitInvokeDynamicInsn(
                            String name, String descriptor, Handle bootstrap, Object... arguments) {
                        // Capturing sites build an instance per evaluation; the JDK's per-site class is cheaper there.
                        if (!bootstrap.getOwner().equals(METAFACTORY)
                                || !bootstrap.getName().equals("metafactory")
                                || Type.getArgumentCount(descriptor) != 0) {
                            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                            return;
                        }
                        var implementation = (Handle) arguments[1];
                        if (!implementation.getOwner().equals(owner)
                                && !implementation.getOwner().startsWith("[")
                                && implementation.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                            super.visitInvokeDynamicInsn(
                                    name,
                                    descriptor,
                                    new Handle(
                                            Opcodes.H_INVOKESTATIC,
                                            SHARED_LAMBDAS,
                                            "lazyMetafactory",
                                            LAZY_DESCRIPTOR,
                                            false),
                                    arguments[0],
                                    implementation.getTag(),
                                    implementation.getOwner(),
                                    implementation.getName(),
                                    implementation.getDesc(),
                                    ((Type) arguments[2]).getDescriptor());
                        } else {
                            super.visitInvokeDynamicInsn(
                                    name,
                                    descriptor,
                                    new Handle(
                                            Opcodes.H_INVOKESTATIC,
                                            SHARED_LAMBDAS,
                                            bootstrap.getName(),
                                            bootstrap.getDesc(),
                                            false),
                                    arguments);
                        }
                    }
                };
            }
        };
        ClassVisitor guarded = ModelConcurrency.visitor(internalName, shrink);
        guarded = ac.cult.runtime.RegistryReadTransform.visitor(
                internalName, guarded, "ac/cult/placement/runtime/RequestTags");
        new ClassReader(bytes)
                .accept(
                        narrowed && ModelNarrowing.applies(internalName)
                                ? ModelNarrowing.visitor(internalName, guarded)
                                : guarded,
                        0);
        return writer.toByteArray();
    }
}
