package ac.cult.cultac.manager.config.update;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Cult-only settings in {@code cult.yml}; v2 adds Bedrock position reconciliation.
     *
     * <p>v2 → v3: FairReach's absolute {@code max-reach} becomes {@code reach-leniency} on top of
     * the player's own reach, so an old limit becomes its distance past the 3-block default.
     */
    public static @NotNull ConfigUpdater.Spec cultSettings() {
        return ConfigUpdater.Spec.builder("/cult/", 3, ConfigUpdater.ConfigFlavor.V2)
                .migration(3, CultConfigSpecs::fairReachLeniency)
                .build();
    }

    private static void fairReachLeniency(MigrationContext ctx) {
        Object maxReach = ctx.input().get("cult.checks.fair-reach.max-reach");
        if (!(maxReach instanceof Number number)) return;
        BigDecimal leniency = new BigDecimal(number.toString()).subtract(BigDecimal.valueOf(3));
        ctx.output().put("cult.checks.fair-reach.reach-leniency", leniency.doubleValue());
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
     *
     * <p>v11 → v12: adds {@code max-clicks-per-tick}; default rewriting adds
     * the key while auto-lift retains any existing operator value.
     *
     * <p>v12 → v13: adds the {@code cloud} block to the bundled config, the same
     * way and with no migration.
     *
     * <p>v13 → v14: adds {@code observe-only}; default rewriting adds the key.
     */
    public static @NotNull ConfigUpdater.Spec mainConfig() {
        return ConfigUpdater.Spec.builder("/config/", 14, ConfigUpdater.ConfigFlavor.V2)
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
                        for (String cat : new String[] {"violation", "session", "player-identity", "setting"}) {
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
                        resourceDirectory,
                        resourceDirectory.equals("/punishments/") ? 4 : 0,
                        ConfigUpdater.ConfigFlavor.V2)
                .cultVersioning();
        if (resourceDirectory.equals("/punishments/")) {
            builder.migration(1, CultConfigSpecs::addBedrockPunishment)
                    .migration(2, CultConfigSpecs::addMissingCheckPunishments)
                    .migration(3, CultConfigSpecs::addNoSneakSlowPunishment)
                    .migration(4, CultConfigSpecs::addSkinBlinkerAndCloudPunishments);
        }
        return builder.build();
    }

    private static void addSkinBlinkerAndCloudPunishments(MigrationContext ctx) {
        addPunishmentChecks(
                ctx,
                "Misc",
                List.of("Exploit"),
                List.of("SkinBlinker"),
                List.of("10:5 [alert]", "1:1 [log]", "20:10 [webhook]", "20:10 [proxy]"),
                "Exploit");
        // A finding is a window's verdict, minutes apart rather than ticks, so they count for longer.
        addPunishmentChecks(
                ctx,
                "Cloud",
                List.of("Cloud"),
                List.of("Cloud"),
                List.of("1:1 [alert]", "1:1 [log]", "3:3 [webhook]", "3:3 [proxy]"),
                null,
                1800);
    }

    private static void addNoSneakSlowPunishment(MigrationContext ctx) {
        addPunishmentChecks(
                ctx,
                "Misc",
                List.of("NoSlow"),
                List.of("NoSneakSlow"),
                List.of("10:5 [alert]", "1:1 [log]", "20:10 [webhook]", "20:10 [proxy]"),
                "NoSlow");
    }

    private static void addMissingCheckPunishments(MigrationContext ctx) {
        addPunishmentChecks(
                ctx,
                "Knockback",
                List.of("Knockback", "Explosion"),
                List.of("KeepSprint"),
                List.of("5:5 [alert]", "1:1 [log]", "20:20 [webhook]", "20:20 [proxy]"));
        addPunishmentChecks(
                ctx,
                "Combat",
                List.of("Interact", "Aim"),
                List.of("AttackPlayerVisibility", "AttackMobVisibility"),
                List.of("20:40 [alert]", "1:1 [log]"));
    }

    private static void addPunishmentChecks(
            MigrationContext ctx,
            String defaultGroup,
            List<String> selectors,
            List<String> additions,
            List<String> defaultCommands) {
        addPunishmentChecks(ctx, defaultGroup, selectors, additions, defaultCommands, null);
    }

    private static void addPunishmentChecks(
            MigrationContext ctx,
            String defaultGroup,
            List<String> selectors,
            List<String> additions,
            List<String> defaultCommands,
            @Nullable String insertAfter) {
        addPunishmentChecks(ctx, defaultGroup, selectors, additions, defaultCommands, insertAfter, 300);
    }

    /**
     * Inserts additions right after the {@code insertAfter} entry when the target group has it, else appends; a group
     * made for them removes violations after {@code removeViolationsAfter} seconds.
     */
    private static void addPunishmentChecks(
            MigrationContext ctx,
            String defaultGroup,
            List<String> selectors,
            List<String> additions,
            List<String> defaultCommands,
            @Nullable String insertAfter,
            int removeViolationsAfter) {
        Map<String, Object> groups = ctx.output().getMap("Punishments");
        if (groups == null) return;
        String target = groups.containsKey(defaultGroup) ? defaultGroup : null;
        if (target == null) {
            for (var entry : groups.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> group) || !(group.get("checks") instanceof List<?> checks))
                    continue;
                if (checks.stream()
                        .anyMatch(value -> selectors.stream()
                                .anyMatch(selector -> selector.equalsIgnoreCase(String.valueOf(value))))) {
                    target = entry.getKey();
                    break;
                }
            }
        }
        List<Object> checks = new ArrayList<>();
        if (target != null) {
            List<?> existing = ctx.output().getList("Punishments." + target + ".checks");
            if (existing != null) checks.addAll(existing);
        }
        int insertAt = -1;
        for (int i = 0; insertAfter != null && i < checks.size(); i++) {
            if (insertAfter.equalsIgnoreCase(String.valueOf(checks.get(i)))) insertAt = i + 1;
        }
        boolean changed = false;
        for (String addition : additions) {
            String name = addition.toLowerCase(Locale.ROOT);
            boolean excluded = groups.values().stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(group -> group.get("checks"))
                    .filter(List.class::isInstance)
                    .map(List.class::cast)
                    .anyMatch(values -> values.stream()
                            .anyMatch(value -> value instanceof String text
                                    && text.startsWith("!")
                                    && name.contains(text.substring(1).toLowerCase(Locale.ROOT))));
            boolean covered = checks.stream()
                    .anyMatch(value -> value instanceof String text
                            && !text.startsWith("!")
                            && name.contains(text.toLowerCase(Locale.ROOT)));
            if (excluded || covered) continue;
            if (insertAt >= 0) {
                checks.add(insertAt++, addition);
            } else {
                checks.add(addition);
            }
            changed = true;
        }
        if (!changed) return;
        if (target == null) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("remove-violations-after", removeViolationsAfter);
            group.put("checks", checks);
            group.put("commands", defaultCommands);
            ctx.output().put("Punishments." + defaultGroup, group);
        } else {
            ctx.output().put("Punishments." + target + ".checks", checks);
        }
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
                    || checks.stream().noneMatch(value -> "Simulation".equalsIgnoreCase(String.valueOf(value))))
                continue;
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

    /**
     * Spec for {@code discord.yml}.
     *
     * <p>v1 → v2: the alert names the player, check and violations in its title and puts every value in a code span.
     * A title or {@code violation-content} still in a shipped v1 shape, in any language, takes the new default; one
     * the operator wrote stays. {@code include-state} is added by the default rewrite.
     */
    public static @NotNull ConfigUpdater.Spec discord() {
        return ConfigUpdater.Spec.builder("/discord/", 2, ConfigUpdater.ConfigFlavor.V2)
                .migration(2, CultConfigSpecs::discordAlertLayout)
                .build();
    }

    private static final Pattern SHIPPED_DISCORD_TITLE = Pattern.compile("\\*\\*(?:Cult|Grim) [^%*]+\\*\\*");
    private static final Pattern SHIPPED_DISCORD_LINE = Pattern.compile("\\*\\*[^*]+\\*\\*: (`?)%([a-z_]+)%\\1");
    private static final List<String> SHIPPED_DISCORD_PLACEHOLDERS =
            List.of("player", "check", "violations", "version", "brand", "ping", "tps");

    static void discordAlertLayout(MigrationContext ctx) {
        String title = ctx.input().getString("embed-title");
        String newTitle = ctx.defaults().getString("embed-title");
        if (title != null
                && newTitle != null
                && SHIPPED_DISCORD_TITLE.matcher(title).matches()) {
            ctx.output().put("embed-title", newTitle);
        }
        List<?> content = ctx.input().getList("violation-content");
        List<?> newContent = ctx.defaults().getList("violation-content");
        if (content != null && newContent != null && isShippedDiscordContent(content)) {
            ctx.output().put("violation-content", newContent);
        }
    }

    private static boolean isShippedDiscordContent(List<?> content) {
        if (content.size() != SHIPPED_DISCORD_PLACEHOLDERS.size()) return false;
        for (int i = 0; i < content.size(); i++) {
            if (!(content.get(i) instanceof String line)) return false;
            Matcher matcher = SHIPPED_DISCORD_LINE.matcher(line);
            if (!matcher.matches() || !matcher.group(2).equals(SHIPPED_DISCORD_PLACEHOLDERS.get(i))) return false;
        }
        return true;
    }

    public static @NotNull ConfigUpdater.Spec messages() {
        return ConfigUpdater.Spec.builder("/messages/", 3, ConfigUpdater.ConfigFlavor.V2)
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
                "/databases/" + backendId + "/", backendVersion(backendId), ConfigUpdater.ConfigFlavor.V2);
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

    private static void preservePoolSettingOverrides(@NotNull MigrationContext ctx, @NotNull String backendId) {
        // v1 did not ship these keys, but preserve values if an operator
        // already added them by hand before the bundled defaults caught up.
        String prefix = backendId + ".pool-settings.";
        for (String key : new String[] {
            "maximum-pool-size", "minimum-idle", "maximum-lifetime-ms", "keepalive-time-ms", "connection-timeout-ms"
        }) {
            Object value = ctx.input().get(prefix + key);
            if (value != null) {
                ctx.output().put(prefix + key, value);
            }
        }
    }
}
