package ac.cult.cultac.manager.config.update;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Centralised registry of {@link ConfigUpdater.Spec} instances for every
 * Cult config file. Each yml file owns:
 *
 * <ul>
 *   <li>a {@code config-flavor} marker (V2 / V3) that the updater rejects on
 *       a mismatch — a wrong-flavor file fails fast instead of silently
 *       flat-merging into the wrong key set;</li>
 *   <li>a {@code config-version} integer bumped on every breaking shape
 *       change to the bundled default;</li>
 *   <li>an optional in-version {@link ConfigUpdater.Migration} chain that
 *       lifts values forward across each version step. Multiple steps
 *       stack — v3 → v8 runs each registered v4/v5/v6/v7/v8 migration.</li>
 * </ul>
 *
 * <p>Resource paths name the directory ({@code "/database/"}) — the updater
 * picks the language-appropriate {@code en.yml} / {@code zh.yml} / … from
 * inside it at update time, falling back to {@code en.yml} when the active
 * locale isn't bundled. Same shape Configuralize uses for runtime loading.
 *
 * <p>Migrations write through the {@link MigrationContext} API: typed
 * dotted-path reads from {@code input()}, writes to {@code output()},
 * cross-file pushes via {@code otherFile(name)}. The updater applies
 * recorded write ops via the line-mapped patcher so the bundled default's
 * comments survive the rewrite.
 *
 * <p>{@link #cultConfig} supplies a separate, in-place Cult migration chain,
 * including for operator-authored punishment groups. Legacy config-version
 * values and migration steps remain independent.
 */
@UtilityClass
public final class CultConfigSpecs {

    /** Cult-only settings in {@code cult.yml}; versioned independently of the upstream files. */
    public static @NotNull ConfigUpdater.Spec cultSettings() {
        return ConfigUpdater.Spec.builder("/cult/", 1, ConfigUpdater.ConfigFlavor.V2)
                .build();
    }

    /**
     * Spec for the main {@code config.yml}.
     *
     * <p>v9 → v10: migrates Cult 2.x's {@code history:} block out of
     * config.yml and into the new {@code database.yml} + the matching
     * {@code databases/<id>.yml}. Pre-V2 operators had all DB settings
     * inline in config.yml; V2 splits the datastore config into its own
     * file. The bundled default at v10 has no {@code history:} block, so
     * the auto-lift naturally drops it from the new file; the migration
     * only needs to ferry the values over.
     *
     * <p>v10 → v11: adds {@code update-permission-ticks} to the bundled
     * config. No explicit migration is needed; the updater's default rewrite
     * adds the key, and auto-lift preserves an existing user value if present.
     */
    public static @NotNull ConfigUpdater.Spec mainConfig() {
        return ConfigUpdater.Spec.builder("/config/", 11, ConfigUpdater.ConfigFlavor.V2)
                .migration(10, ctx -> {
                    String typeRaw = ctx.input().getString("history.database.type");
                    String type = typeRaw == null ? null : typeRaw.trim().toUpperCase(Locale.ROOT);
                    String backendId = backendIdFor(type);
                    if (backendId != null) {
                        // Route every relational category to the operator's
                        // chosen backend so the new layout matches the old
                        // single-DB shape. blob is intentionally absent —
                        // none of the SQL backends declare support for it
                        // (would fail capability validation); leave it on
                        // whatever the bundled default routes blob to.
                        for (String cat : new String[]{"violation", "session", "player-identity", "setting"}) {
                            ctx.otherFile("database.yml").put("database.routing." + cat, backendId);
                        }
                    }
                    // Carry the rendering settings over — these moved out of
                    // history: into database.* with the same names.
                    Integer entriesPerPage = ctx.input().getInt("history.entries-per-page");
                    if (entriesPerPage != null) {
                        ctx.otherFile("database.yml").put("database.history.entries-per-page", entriesPerPage);
                    }
                    String serverName = ctx.input().getString("history.server-name");
                    if (serverName != null) {
                        ctx.otherFile("database.yml").put("database.server-name", serverName);
                    }
                    if (backendId != null && !backendId.equals("sqlite")) {
                        // Connection details only matter for networked backends.
                        // SQLite settings (file path) stay at their defaults.
                        String target = "databases/" + backendId + ".yml";
                        Object host = ctx.input().get("history.database.host");
                        Object port = ctx.input().get("history.database.port");
                        Object db = ctx.input().get("history.database.database");
                        Object user = ctx.input().get("history.database.username");
                        Object pass = ctx.input().get("history.database.password");
                        if (host != null) ctx.otherFile(target).put(backendId + ".host", host);
                        if (port != null) ctx.otherFile(target).put(backendId + ".port", port);
                        if (db != null) ctx.otherFile(target).put(backendId + ".database", db);
                        if (user != null) ctx.otherFile(target).put(backendId + ".user", user);
                        if (pass != null) ctx.otherFile(target).put(backendId + ".password", pass);
                    }
                })
                .build();
    }

    /** Bump this per-file Cult revision and register steps here, leaving legacy specs unchanged. */
    public static @NotNull ConfigUpdater.Spec cultConfig(String resourceDirectory) {
        ConfigUpdater.Spec.Builder builder = ConfigUpdater.Spec.builder(
                resourceDirectory, resourceDirectory.equals("/punishments/") ? 1 : 0,
                ConfigUpdater.ConfigFlavor.V2).cultVersioning();
        if (resourceDirectory.equals("/punishments/")) {
            builder.migration(1, CultConfigSpecs::addBedrockPunishment);
        }
        return builder.build();
    }

    private static void addBedrockPunishment(MigrationContext ctx) {
        Map<String, Object> groups = ctx.input().getMap("Punishments");
        if (groups == null) return;
        // Respect existing Bedrock routes and explicit exclusions, including broad selectors.
        for (Object value : groups.values()) {
            if (!(value instanceof Map<?, ?> group) || !(group.get("checks") instanceof List<?> checks)) continue;
            for (Object selector : checks) {
                if (!(selector instanceof String text)) continue;
                String match = text.toLowerCase(Locale.ROOT);
                if (match.startsWith("!")) match = match.substring(1);
                if ("bedrockmovement".contains(match)) return;
            }
        }
        for (Map.Entry<String, Object> entry : groups.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> group)
                    || !(group.get("checks") instanceof List<?> checks)
                    || checks.stream().noneMatch(value -> "Simulation".equalsIgnoreCase(String.valueOf(value)))) continue;
            List<Object> updated = new ArrayList<>(checks);
            updated.add("BedrockMovement");
            ctx.output().put("Punishments." + entry.getKey() + ".checks", updated);
            return;
        }
    }

    private static @Nullable String backendIdFor(@Nullable String legacyType) {
        if (legacyType == null) return null;
        return switch (legacyType) {
            case "SQLITE" -> "sqlite";
            case "MYSQL" -> "mysql";
            case "POSTGRESQL", "POSTGRES" -> "postgres";
            default -> null;
        };
    }

    public static @NotNull ConfigUpdater.Spec discord() {
        return ConfigUpdater.Spec.builder("/discord/", 1, ConfigUpdater.ConfigFlavor.V2)
                .build();
    }

    public static @NotNull ConfigUpdater.Spec messages() {
        return ConfigUpdater.Spec.builder("/messages/", 2, ConfigUpdater.ConfigFlavor.V2)
                .build();
    }

    /**
     * The datastore router config (top-level {@code database:} wrapper).
     * No own-file migrations — operators arriving from Cult 2.x have no
     * existing database.yml on disk; the legacy lift happens on the
     * config.yml side via cross-file writes (see {@link #mainConfig}).
     */
    public static @NotNull ConfigUpdater.Spec database() {
        return ConfigUpdater.Spec.builder("/database/", 1, ConfigUpdater.ConfigFlavor.V2)
                .build();
    }

    /**
     * Per-backend file at {@code databases/<id>/en.yml}. Each backend's
     * settings live under a top-level {@code <id>:} wrapper so all
     * per-backend files load through the shared ConfigManager without
     * key collisions across backends.
     */
    public static @NotNull ConfigUpdater.Spec backend(@NotNull String backendId) {
        ConfigUpdater.Spec.Builder builder = ConfigUpdater.Spec.builder(
                "/databases/" + backendId + "/",
                backendVersion(backendId),
                ConfigUpdater.ConfigFlavor.V2);
        if (backendSupportsHikariPoolSettings(backendId)) {
            builder.migration(2, ctx -> preservePoolSettingOverrides(ctx, backendId));
        }
        return builder.build();
    }

    private static int backendVersion(@NotNull String backendId) {
        return backendSupportsHikariPoolSettings(backendId) ? 2 : 1;
    }

    private static boolean backendSupportsHikariPoolSettings(@NotNull String backendId) {
        return backendId.equals("mysql") || backendId.equals("postgres");
    }

    private static void preservePoolSettingOverrides(
            @NotNull MigrationContext ctx,
            @NotNull String backendId) {
        // v1 did not ship these keys, but preserve values if an operator
        // already added them by hand before the bundled defaults caught up.
        String prefix = backendId + ".pool-settings.";
        for (String key : new String[]{
                "maximum-pool-size",
                "minimum-idle",
                "maximum-lifetime-ms",
                "keepalive-time-ms",
                "connection-timeout-ms"}) {
            Object value = ctx.input().get(prefix + key);
            if (value != null) {
                ctx.output().put(prefix + key, value);
            }
        }
    }
}
