package ac.cult.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class EngineClassLoaderTest {
    @Test
    void transformedClassesKeepTheVerifiedVanillaPackageSigners() throws Exception {
        var runtime = Path.of(System.getProperty("vanillaRuntime"));
        var urls = new ArrayList<java.net.URL>();
        urls.add(runtime.resolve("client.jar").toUri().toURL());
        try (var libraries = Files.walk(runtime.resolve("lib"))) {
            for (var library :
                    libraries.filter(path -> path.toString().endsWith(".jar")).toList())
                urls.add(library.toUri().toURL());
        }
        try (var loader = new EngineClassLoader(
                urls.toArray(java.net.URL[]::new), getClass().getClassLoader())) {
            var original = Class.forName("net.minecraft.core.Vec3i", false, loader);
            var source = original.getProtectionDomain().getCodeSource();
            assertNotNull(source.getCodeSigners(), "Use the official signed runtime in this regression");
            for (String name : java.util.List.of(
                    "net.minecraft.core.MappedRegistry",
                    "net.minecraft.core.Holder$Reference",
                    "net.minecraft.core.HolderSet$Named")) {
                var transformed = Class.forName(name, false, loader);
                assertSame(loader, transformed.getClassLoader());
                var transformedSource = transformed.getProtectionDomain().getCodeSource();
                assertEquals(source.getLocation(), transformedSource.getLocation());
                assertArrayEquals(source.getCodeSigners(), transformedSource.getCodeSigners());
            }
        }
    }
}
