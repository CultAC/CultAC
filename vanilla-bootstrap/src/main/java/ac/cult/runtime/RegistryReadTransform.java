package ac.cult.runtime;

import org.objectweb.asm.*;

/** Redirects registry reads in an owned vanilla loader, preserving vanilla's default fallback. */
public final class RegistryReadTransform {
    private RegistryReadTransform() {}

    public static boolean applies(String name) {
        return name.equals("net.minecraft.core.Holder$Reference")
                || name.equals("net.minecraft.core.HolderSet$Named")
                || name.equals("net.minecraft.core.MappedRegistry");
    }

    public static byte[] apply(String name, byte[] bytes, String context) {
        if (!applies(name)) return bytes;
        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(visitor(name.replace('.', '/'), writer, context), 0);
        return writer.toByteArray();
    }

    public static ClassVisitor visitor(String owner, ClassVisitor next, String context) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String descriptor, String signature, String[] exceptions) {
                var target = super.visitMethod(access, name, descriptor, signature, exceptions);
                String field = null, helper = null;
                if (owner.equals("net/minecraft/core/Holder$Reference")) {
                    if (name.equals("boundTags") && descriptor.equals("()Ljava/util/Set;")) {
                        field = "tags";
                        helper = "holderTags";
                    }
                    if (name.equals("components")
                            && descriptor.equals("()Lnet/minecraft/core/component/DataComponentMap;")) {
                        field = "components";
                        helper = "holderComponents";
                    }
                } else if (owner.equals("net/minecraft/core/HolderSet$Named")
                        && name.equals("contents")
                        && descriptor.equals("()Ljava/util/List;")) {
                    field = "contents";
                    helper = "tagContents";
                }
                if (field != null) {
                    String backingField = field, method = helper;
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override
                        public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldType) {
                            if (opcode != Opcodes.GETFIELD
                                    || !fieldOwner.equals(owner)
                                    || !fieldName.equals(backingField)) {
                                super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldType);
                                return;
                            }
                            // Leave the original control flow and stack-map frames intact.
                            super.visitInsn(Opcodes.DUP);
                            super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldType);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    context,
                                    method,
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false);
                            super.visitTypeInsn(
                                    Opcodes.CHECKCAST, Type.getType(fieldType).getInternalName());
                        }
                    };
                }
                if (!owner.equals("net/minecraft/core/MappedRegistry")
                        || !name.equals("get")
                        || !descriptor.equals("(Lnet/minecraft/tags/TagKey;)Ljava/util/Optional;")) return target;
                // A connection can introduce named tags absent from the bound default registry.
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                context,
                                "registryTag",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                false);
                        super.visitInsn(Opcodes.DUP);
                        var fallback = new Label();
                        super.visitJumpInsn(Opcodes.IFNULL, fallback);
                        super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Optional");
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(fallback);
                        super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"});
                        super.visitInsn(Opcodes.POP);
                    }
                };
            }
        };
    }
}
