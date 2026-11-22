package ac.cult.reload.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

public record RuntimeArtifact(String build, String entrypoint, String digest) {
    public String identity() {
        return build + "@" + digest;
    }

    public String display() {
        return build + "@" + digest.substring(0, 12);
    }

    static void validateHost(Path jar, String hostVersion) throws Exception {
        if (hostVersion == null) return;
        try (var archive = new JarFile(jar.toFile())) {
            var fields = archive.getManifest().getMainAttributes();
            String minimum = fields.getValue("Cult-Runtime-Host-Min");
            String maximum = fields.getValue("Cult-Runtime-Host-Max");
            if (minimum == null || maximum == null) {
                String required = fields.getValue("Cult-Runtime-Host-Version");
                if (required == null || !required.equals(hostVersion))
                    throw new IOException("Runtime host version " + required + " does not match " + hostVersion);
                return;
            }
            if (!supports(minimum, maximum, hostVersion))
                throw new IOException(
                        "Runtime supports host versions " + minimum + " to " + maximum + ", not " + hostVersion);
        }
    }

    /** The maximum covers its own patch releases: a maximum of 26.3 accepts 26.3.1. */
    static boolean supports(String minimum, String maximum, String hostVersion) {
        int[] host = numbers(hostVersion);
        int[] upper = numbers(maximum);
        return compare(host, numbers(minimum), Integer.MAX_VALUE) >= 0 && compare(host, upper, upper.length) <= 0;
    }

    private static int compare(int[] left, int[] right, int length) {
        for (int i = 0; i < Math.min(length, Math.max(left.length, right.length)); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static int[] numbers(String version) {
        var parts = version.trim().split("[^0-9.]", 2)[0].split("\\.");
        var result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) throw new IllegalArgumentException("Invalid version " + version);
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }

    public static RuntimeArtifact inspect(Path jar, String platform, String loaderVersion) throws Exception {
        try (var archive = new JarFile(jar.toFile(), true)) {
            var manifest = archive.getManifest();
            if (manifest == null) throw new IOException("Missing runtime manifest");
            var fields = manifest.getMainAttributes();
            if (!"1".equals(fields.getValue("Cult-Runtime-SPI"))) throw new IOException("Incompatible runtime SPI");
            if (!platform.equals(fields.getValue("Cult-Runtime-Platform")))
                throw new IOException("Wrong runtime platform");
            int minimumJava = Integer.parseInt(fields.getValue("Cult-Runtime-Java"));
            if (minimumJava > Runtime.version().feature())
                throw new IOException("Runtime requires Java " + minimumJava);
            String entry = fields.getValue("Cult-Runtime-Entrypoint");
            var descriptor = archive.getJarEntry(PluginDescriptors.name(platform));
            if (descriptor == null) throw new IOException("Missing plugin descriptor");
            String build;
            try (var input = archive.getInputStream(descriptor)) {
                var bytes = input.readAllBytes();
                build = PluginDescriptors.version(bytes, platform);
                LoaderVersions.require(PluginDescriptors.loaderVersion(bytes, platform), loaderVersion);
            }
            if (entry == null
                    || build == null
                    || build.isBlank()
                    || archive.getJarEntry(entry.replace('.', '/') + ".class") == null) {
                throw new IOException("Incomplete runtime manifest");
            }
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (name.startsWith("ac/cult/reload/") || name.startsWith("ac/grim/grimac/api/")) {
                    throw new IOException("Runtime bundles shared ABI: " + name);
                }
            }
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(jar)) {
                var buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return new RuntimeArtifact(build, entry, HexFormat.of().formatHex(digest.digest()));
        }
    }
}
