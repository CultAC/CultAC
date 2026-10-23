package ac.cult.cultac.cloud;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The rest of the anticheat reaches the cloud upload only through {@link CloudModule}. */
class CloudBoundaryTest {
    private static final List<String> MODULES = List.of("common", "bukkit", "velocity-platform");
    private static final Pattern CLOUD_CLASS =
            Pattern.compile("ac\\.cult\\.cultac\\.(?:cloud|platform\\.\\w+\\.cloud)\\.[\\w.]*[A-Z]\\w*");
    private static final Pattern CLOUD_PACKAGE = Pattern.compile("ac/cult/cultac/(?:cloud|platform/\\w+/cloud)/.*");
    private static final String MODULE = CloudModule.class.getName();

    /** Files outside the cloud packages that use more of it than {@link CloudModule}, and why. */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("ac/cult/cultac/command/commands/CloudArguments.java", "cloud command: the findings log"),
            Map.entry("ac/cult/cultac/command/commands/CultFindings.java", "cloud command: the findings log"),
            Map.entry("ac/cult/cultac/command/commands/CultTop.java", "cloud command: the findings log"),
            Map.entry("ac/cult/cultac/command/commands/FindingLines.java", "cloud command: the findings log"),
            Map.entry("ac/cult/cultac/command/commands/CultLabel.java", "cloud command: the label service"),
            Map.entry("ac/cult/cultac/command/commands/CultLabelMenu.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/LabelConfirm.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/LabelDialogs.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/LabelLines.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/LabelMenu.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/LabelWizard.java", "cloud command: the label menu"),
            Map.entry("ac/cult/cultac/command/commands/MojangProfiles.java", "cloud command: the label service"),
            Map.entry("ac/cult/cultac/command/commands/TargetLookup.java", "cloud command: the label service"),
            Map.entry("ac/cult/cultac/manager/portal/PortalSync.java", "team sync: its messages and team sockets"),
            Map.entry(
                    "ac/cult/cultac/manager/portal/PortalSyncService.java",
                    "team sync: the upload's settings and team sockets"),
            Map.entry(
                    "ac/cult/cultac/platform/bukkit/CultACBukkitLoaderPlugin.java",
                    "hands the server's cloud glue to CloudModule"),
            Map.entry(
                    "ac/cult/cultac/platform/velocity/VelocityPlatform.java",
                    "hands the proxy's cloud glue to CloudModule"),
            Map.entry("ac/cult/cultac/platform/velocity/VelocityPlayer.java", "reads its backend's facts"));

    @Test
    void onlyCloudModuleIsUsedOutsideTheCloudPackages() throws IOException {
        Path root = Path.of("").toAbsolutePath().getParent();
        List<String> leaks = new ArrayList<>();
        Set<String> allowedInUse = new TreeSet<>();
        for (String module : MODULES) {
            Path sources = root.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(sources), sources.toString());
            List<Path> files;
            try (Stream<Path> walk = Files.walk(sources)) {
                files = walk.filter(file -> file.toString().endsWith(".java")).toList();
            }
            for (Path file : files) {
                String path = sources.relativize(file).toString().replace('\\', '/');
                if (CLOUD_PACKAGE.matcher(path).matches()) continue;
                Matcher used = CLOUD_CLASS.matcher(Files.readString(file));
                while (used.find()) {
                    if (used.group().equals(MODULE)) continue;
                    if (ALLOWED.containsKey(path)) allowedInUse.add(path);
                    else leaks.add(module + "/" + path + " uses " + used.group());
                }
            }
        }
        assertEquals(List.of(), leaks);
        assertEquals(new TreeSet<>(ALLOWED.keySet()), allowedInUse, "an allowed file no longer needs it");
    }
}
