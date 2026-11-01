package ac.cult.reload.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The {@code update} section of {@code config.yml}, read by the loader itself so {@code /cult update <build>} works
 * before any runtime has started. Each environment variable, when set and not blank, wins over its key.
 *
 * @param repository the GitHub {@code owner/repo} whose {@code build-<N>} releases are installed
 * @param token sent to the GitHub API only; empty for anonymous requests
 * @param publicKey the Ed25519 key manifests are signed with, base64 X.509 DER
 * @param webhook where each update's result line is posted; empty for none
 * @param stateFile where the installed build number is kept for the next boot
 * @param pinnedBuild the build to start on boot when the state file names none; 0 for none
 * @param followBranch the channel whose newest build every boot starts, over the state file and pinned build; empty
 *     for the bundled jar's own channel, null to follow none. {@code build: latest} or {@code latest:<channel>}, the
 *     channel being the branch name except main's {@code stable}
 */
public record UpdateSettings(
        String repository,
        String token,
        String publicKey,
        String webhook,
        Path stateFile,
        long pinnedBuild,
        String followBranch) {
    /** The official builds' signing key. */
    public static final String OFFICIAL_PUBLIC_KEY = "MCowBQYDK2VwAyEAxE6sl2rSssawl6S3SA62aWafFKFfvnI5EAkzdQXvBOM=";

    private static final Pattern FOLLOW = Pattern.compile("(?i:latest)(?:\\s*:\\s*([A-Za-z0-9._/-]{0,200}))?");

    public UpdateSettings(
            String repository, String token, String publicKey, String webhook, Path stateFile, long pinnedBuild) {
        this(repository, token, publicKey, webhook, stateFile, pinnedBuild, null);
    }

    /**
     * @param dataDirectory the plugin's data directory, holding {@code config.yml} and the default state file
     * @param defaultRepository the {@code owner/repo} this jar was built from
     */
    public static UpdateSettings read(Path dataDirectory, String defaultRepository, Function<String, String> env) {
        Map<?, ?> root = Map.of();
        Path config = dataDirectory.resolve("config.yml");
        if (Files.isRegularFile(config)) {
            try {
                var options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object loaded = new Yaml(new SafeConstructor(options)).load(Files.readString(config));
                if (loaded instanceof Map<?, ?> map) root = map;
            } catch (IOException | RuntimeException unreadable) {
                root = Map.of();
            }
        }
        Map<?, ?> update = root.get("update") instanceof Map<?, ?> map ? map : Map.of();
        String repository = value(update, "repository", "CULTAC_UPDATE_REPOSITORY", env);
        String publicKey = value(update, "public-key", "CULTAC_UPDATE_PUBKEY", env);
        String stateFile = value(update, "state-file", "CULTAC_UPDATE_STATE", env);
        String build = value(update, "build", "CULTAC_UPDATE_BUILD", env);
        var follow = FOLLOW.matcher(build);
        return new UpdateSettings(
                repository.isEmpty() ? defaultRepository : repository,
                value(update, "token", "CULTAC_UPDATE_TOKEN", env),
                publicKey.isEmpty() ? OFFICIAL_PUBLIC_KEY : publicKey,
                value(update, "webhook", "CULTAC_UPDATE_WEBHOOK", env),
                stateFile(dataDirectory, stateFile),
                build.matches("[0-9]{1,18}") ? Long.parseLong(build) : 0,
                follow.matches() ? (follow.group(1) == null ? "" : follow.group(1)) : null);
    }

    /** An unusable path falls back to the default, so a typo never stops the loader from starting. */
    private static Path stateFile(Path dataDirectory, String configured) {
        Path fallback = dataDirectory.resolve("update-state.json");
        if (configured.isEmpty()) return fallback;
        try {
            return dataDirectory.resolve(configured);
        } catch (InvalidPathException invalid) {
            return fallback;
        }
    }

    private static String value(Map<?, ?> section, String key, String envName, Function<String, String> env) {
        String value = env.apply(envName);
        if (value == null || value.isBlank()) {
            Object configured = section.get(key);
            value = configured == null ? "" : configured.toString();
        }
        return value.strip();
    }
}
