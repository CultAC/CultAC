package ac.cult.blocksim.generator;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

/** Game code runs in a loader whose parent is the JDK, following CultJavaGenerator's isolation. */
public final class GenerationLauncher {
    public static final String CLIENT_26_3_SHA256 = "4508d006323f24fa02876310c192d739af56516eb259000ac50f0909a68c9a2d";

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 7) throw new IllegalArgumentException("output, vanilla jar, libraries, worker classes, reports and optional block/property handles required");
        Path jar = Path.of(args[1]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if (!hash.equals(CLIENT_26_3_SHA256)) throw new IllegalArgumentException("Refusing unpinned 26.3 vanilla jar: " + hash);
        ArrayList<URL> urls = new ArrayList<>();
        urls.add(Path.of(args[3]).toUri().toURL());
        urls.add(jar.toUri().toURL());
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            if (line.startsWith("-e=")) urls.add(Path.of(line.substring(3)).toUri().toURL());
        }
        var out = System.out;
        var err = System.err;
        var thread = Thread.currentThread();
        var context = thread.getContextClassLoader();
        boolean worldOnly = args.length == 6 && args[5].equals("--world-registries-only");
        try (var loader = new URLClassLoader("block-sim-vanilla-26.3", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            if (!worldOnly) Class.forName("net.minecraft.data.Main", true, loader).getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{"--reports", "--output", args[4]});
            Class.forName("ac.cult.blocksim.generator.VanillaDataGenerator", true, loader).getMethod("main", String[].class)
                .invoke(null, (Object) (worldOnly ? new String[]{"--world-registries-only", args[0], args[1]} : args.length == 7
                    ? new String[]{args[0], args[1], args[4], args[5], args[6]}
                    : args.length == 6 ? new String[]{args[0], args[1], args[4], args[5]}
                    : new String[]{args[0], args[1], args[4]}));
        } finally {
            thread.setContextClassLoader(context);
            System.setOut(out);
            System.setErr(err);
        }
    }
}
