package ac.cult.cultac.vanilla;

import ac.cult.runtime.RegistryReadTransform;
import java.lang.instrument.*;
import java.security.ProtectionDomain;

/** Exercise exactly the production read redirect against the pinned vanilla classes. */
public final class ContextTestAgent {
    public static void premain(String ignored, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (name == null || !RegistryReadTransform.applies(name.replace('/', '.'))) return null;
                if (loader != ContextTestAgent.class.getClassLoader()) return null;
                return RegistryReadTransform.apply(
                        name.replace('/', '.'), bytes, "ac/cult/cultac/vanilla/VanillaContext");
            }
        });
    }
}
