package ac.cult.cultac.manager.config.update;

import ac.cult.cultac.utils.anticheat.LogUtil;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     *
     * <p>v14 → v15: adds the {@code network} block; default rewriting adds it.
     *
     * <p>v15 → v16: one {@code network.uri} picks the transport; see {@link #networkUri}.
     *
     * <p>v16 → v17: {@code network.type} picks the broker, configured in its own block; see {@link #networkBlocks}.
     * Every step reads the user's file as it was, so a v15 config takes the v16 step inside the v17 one.
     */
    public static @NotNull ConfigUpdater.Spec mainConfig() {
        return ConfigUpdater.Spec.builder("/config/", 17, ConfigUpdater.ConfigFlavor.V2)
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
                .migration(17, CultConfigSpecs::networkBlocks)
                .build();
    }

    /**
     * Builds {@code network.uri} from {@code network.transport} with {@code network.redis.uri}, or with
     * {@code network.nats.servers} and {@code network.nats.token}, the token becoming each server's user info:
     * {@code none} is empty and {@code redisbungee} stays {@code redisbungee}. {@code network.nats.credentials-file}
     * becomes {@code network.nats-credentials-file}; {@code network.network} is dropped.
     */
    private static String networkUri(YamlMap input) {
        String transport = input.getString("network.transport");
        return switch (transport == null ? "" : transport.strip().toLowerCase(Locale.ROOT)) {
            case "redis" -> text(input, "network.redis.uri");
            case "nats" -> natsUri(input);
            case "redisbungee" -> "redisbungee";
            default -> "";
        };
    }

    /**
     * Writes {@code network.type} and the chosen broker's block from {@code network.uri}, the {@link #networkUri} of a
     * v15 config: empty is {@code none}, {@code redisbungee} stays; a {@code redis://} or {@code rediss://} URI gives
     * the address, the decoded user and password and {@code ssl} for {@code rediss}, without its database; NATS
     * servers give their addresses, the first user info as token or user and password, and {@code ssl} when any is
     * {@code tls://}. {@code nats-credentials-file} moves into the NATS block. A URI that does not read is
     * {@code none}, with one warning. The other block gets the bundled defaults. A non-blank {@code network.secret}
     * is kept; the bundled config has none, so a blank one is dropped.
     */
    static void networkBlocks(MigrationContext ctx) {
        YamlMap input = ctx.input();
        Integer version = input.getInt("config-version");
        boolean v15 = version == null || version < 16;
        String uri = v15 ? networkUri(input) : text(input, "network.uri");
        String credentialsFile = text(input, v15 ? "network.nats.credentials-file" : "network.nats-credentials-file");
        Broker broker;
        try {
            broker = broker(uri);
        } catch (URISyntaxException | IllegalArgumentException unreadable) {
            LogUtil.warn("config.yml: network.uri cannot be read, so network.type is none; set the network block by"
                    + " hand (docs/network.md in CultAC's source explains it)");
            broker = Broker.NONE;
        }
        YamlMap output = ctx.output();
        output.put("network.type", broker.type());
        boolean redis = broker.type().equals("redis");
        boolean nats = broker.type().equals("nats");
        output.put("network.redis.address", redis ? broker.address() : "localhost:6379");
        output.put("network.redis.username", redis ? broker.username() : "");
        output.put("network.redis.password", redis ? broker.password() : "");
        output.put("network.redis.ssl", redis && broker.ssl());
        output.put("network.nats.address", nats ? broker.address() : "localhost:4222");
        output.put("network.nats.username", nats ? broker.username() : "");
        output.put("network.nats.password", nats ? broker.password() : "");
        output.put("network.nats.token", nats ? broker.token() : "");
        output.put("network.nats.credentials-file", credentialsFile);
        output.put("network.nats.ssl", nats && broker.ssl());
        Object secret = input.get("network.secret");
        if (secret != null && !String.valueOf(secret).isBlank()) output.put("network.secret", secret);
    }

    /** One broker's settings as a {@code network.uri} named them. */
    private record Broker(String type, String address, String username, String password, String token, boolean ssl) {
        static final Broker NONE = new Broker("none", "", "", "", "", false);
    }

    private static final Pattern URI_SCHEME = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://");

    private static Broker broker(String uri) throws URISyntaxException {
        if (uri.isEmpty()) return Broker.NONE;
        if (uri.equalsIgnoreCase("redisbungee")) return new Broker("redisbungee", "", "", "", "", false);
        String scheme = scheme(uri);
        if (scheme.equals("redis") || scheme.equals("rediss")) {
            if (URI_SCHEME.matcher(uri).results().count() != 1) throw new IllegalArgumentException("several URIs");
            URI parsed = withHost(uri);
            String userInfo = parsed.getRawUserInfo();
            String username = "";
            String password = "";
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                username = colon < 0 ? "" : decode(userInfo.substring(0, colon));
                password = decode(colon < 0 ? userInfo : userInfo.substring(colon + 1));
            }
            return new Broker("redis", address(parsed, 6379), username, password, "", scheme.equals("rediss"));
        }
        List<String> addresses = new ArrayList<>();
        String username = "";
        String password = "";
        String token = "";
        boolean credential = false;
        boolean ssl = false;
        for (String server : uri.split(",")) {
            String stripped = server.strip();
            if (stripped.isEmpty()) continue;
            String serverScheme = scheme(stripped);
            if (!serverScheme.equals("nats") && !serverScheme.equals("tls"))
                throw new IllegalArgumentException("not a NATS server");
            ssl |= serverScheme.equals("tls");
            URI parsed = withHost(stripped);
            addresses.add(address(parsed, 4222));
            String userInfo = parsed.getRawUserInfo();
            if (credential || userInfo == null || userInfo.isEmpty() || userInfo.equals(":")) continue;
            credential = true;
            int colon = userInfo.indexOf(':');
            if (colon < 0) {
                token = decode(userInfo);
            } else {
                username = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            }
        }
        if (addresses.isEmpty()) throw new IllegalArgumentException("no server");
        return new Broker("nats", String.join(", ", addresses), username, password, token, ssl);
    }

    private static String scheme(String uri) {
        Matcher scheme = URI_SCHEME.matcher(uri);
        return scheme.lookingAt() ? scheme.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static URI withHost(String uri) throws URISyntaxException {
        URI parsed = new URI(uri);
        if (parsed.getHost() == null || parsed.getHost().isEmpty()) throw new URISyntaxException(uri, "no host");
        return parsed;
    }

    private static String address(URI parsed, int defaultPort) {
        return parsed.getHost() + ":" + (parsed.getPort() < 0 ? defaultPort : parsed.getPort());
    }

    /** Percent-decodes URI user info, where {@code +} is a plus sign. */
    private static String decode(String userInfo) {
        return URLDecoder.decode(userInfo.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String natsUri(YamlMap input) {
        String token = URLEncoder.encode(text(input, "network.nats.token"), StandardCharsets.UTF_8)
                .replace("+", "%20");
        List<?> listed = input.getList("network.nats.servers");
        List<String> servers = new ArrayList<>();
        if (listed != null) {
            for (Object server : listed) {
                String text = server == null ? "" : server.toString().strip();
                if (text.isEmpty()) continue;
                int scheme = text.indexOf("://");
                boolean named = text.indexOf('@', scheme < 0 ? 0 : scheme) >= 0;
                if (!token.isEmpty() && scheme >= 0 && !named)
                    text = text.substring(0, scheme + 3) + token + "@" + text.substring(scheme + 3);
                servers.add(text);
            }
        }
        return String.join(",", servers);
    }

    private static String text(YamlMap input, String key) {
        String value = input.getString(key);
        return value == null ? "" : value.strip();
    }

    /** Bump this per-file Cult revision and register steps here, leaving legacy specs unchanged. */
    public static @NotNull ConfigUpdater.Spec cultConfig(String resourceDirectory) {
        ConfigUpdater.Spec.Builder builder = ConfigUpdater.Spec.builder(
                        resourceDirectory,
                        resourceDirectory.equals("/punishments/") ? 5 : 0,
                        ConfigUpdater.ConfigFlavor.V2)
                .cultVersioning();
        if (resourceDirectory.equals("/punishments/")) {
            builder.migration(1, CultConfigSpecs::addBedrockPunishment)
                    .migration(2, CultConfigSpecs::addMissingCheckPunishments)
                    .migration(3, CultConfigSpecs::addNoSneakSlowPunishment)
                    .migration(4, CultConfigSpecs::addSkinBlinkerAndCloudPunishments)
                    .migration(5, CultConfigSpecs::excludeCloudFromDefaultMisc);
        }
        return builder.build();
    }

    /** {@code Misc}'s selectors as revision 4 shipped them. */
    private static final List<String> DEFAULT_MISC_CHECKS = List.of(
            "Vehicle",
            "NoSlow",
            "NoSneakSlow",
            "Sprint",
            "MultiActions",
            "Place",
            "Baritone",
            "Break",
            "TransactionOrder",
            "Elytra",
            "Chat",
            "Exploit",
            "SkinBlinker");

    private static final List<String> DEFAULT_MISC_COMMANDS =
            List.of("10:5 [alert]", "1:1 [log]", "20:10 [webhook]", "20:10 [proxy]");

    /** Keeps an unmodified default {@code Misc} group's {@code Exploit} selector off the Cloud checks. */
    private static void excludeCloudFromDefaultMisc(MigrationContext ctx) {
        Map<String, Object> misc = ctx.output().getMap("Punishments.Misc");
        if (misc == null
                || !DEFAULT_MISC_CHECKS.equals(misc.get("checks"))
                || !DEFAULT_MISC_COMMANDS.equals(misc.get("commands"))
                || !Integer.valueOf(300).equals(misc.get("remove-violations-after"))) return;
        List<Object> checks = new ArrayList<>(DEFAULT_MISC_CHECKS);
        checks.add("!Cloud");
        ctx.output().put("Punishments.Misc.checks", checks);
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
     * v2 → v3: the description takes Grim's alert layout (location, both pings, clock, session time, sensitivity,
     * state, checks and verbose) and the footer and thumbnail keys are written out. A title or {@code
     * violation-content} still in a shipped shape, in any language, takes the new default; one the operator wrote
     * stays. The new layout leaves out the state or verbose line the operator turned off. {@code include-state} is
     * added by the default rewrite; {@code embed-image-url} and {@code include-verbose}, which no bundled file
     * carries, are kept.
     * v4 → v5: the description goes back to one value per line, with verbose and state as fields, and the footer
     * shows only the version. A file from before v4 has its shipped sensitivity line in percentages first. A
     * description still in the shipped v3/v4 layout, in any language and with or without its state and verbose lines,
     * takes the new default, and the shipped footer text does too; anything the operator wrote stays.
     */
    public static @NotNull ConfigUpdater.Spec discord() {
        return ConfigUpdater.Spec.builder("/discord/", 5, ConfigUpdater.ConfigFlavor.V2)
                .migration(2, CultConfigSpecs::discordAlertLayout)
                .migration(3, CultConfigSpecs::discordAlertLayout)
                .migration(5, CultConfigSpecs::discordValuePerLine)
                .build();
    }

    private static final Pattern SHIPPED_DISCORD_TITLE = Pattern.compile("\\*\\*(?:Cult|Grim|Gladiator) "
            + "(?:Alert|Alerta|Alerte|Avviso|Uyarı|Waarschuwing|Warnung|Оповещение|警告|警报)\\*\\*");
    private static final Pattern SHIPPED_DISCORD_LINE = Pattern.compile("\\*\\*([^*]+)\\*\\*: (.+)");
    private static final List<String> SHIPPED_V1_DISCORD_PLACEHOLDERS =
            List.of("player", "check", "violations", "version", "brand", "ping", "tps");
    private static final List<String> SHIPPED_V2_DISCORD_VALUES = List.of(
            "`%player%`",
            "`%uuid%`",
            "`%check%`",
            "`%version%`",
            "`%brand%`",
            "`H %h_sensitivity%% / V %v_sensitivity%%`",
            "`%ping%`",
            "`%tps%`",
            "`%server%`",
            "`%world% (%world_type%)`");
    private static final List<List<String>> SHIPPED_V2_DISCORD_LABELS = List.of(
            List.of(
                    "Player",
                    "Spieler",
                    "Jugador",
                    "Joueur",
                    "Giocatore",
                    "プレイヤー",
                    "Speler",
                    "Jogador",
                    "Игрок",
                    "Oyuncu",
                    "玩家名"),
            List.of("UUID"),
            List.of(
                    "Check",
                    "Überprüfung",
                    "Comprobación",
                    "Vérification",
                    "Controllo",
                    "違反項目",
                    "Controle",
                    "Verificação",
                    "Проверка",
                    "Kontrol",
                    "检查"),
            List.of(
                    "Client Version",
                    "Client-Version",
                    "Version del cliente",
                    "Version du client",
                    "Versione del client",
                    "クライアントバージョン",
                    "Client-versie",
                    "Versão do Cliente",
                    "Версия Клиента",
                    "Client Versiyonu",
                    "游戏版本"),
            List.of(
                    "Brand",
                    "Marke",
                    "Marca del cliente",
                    "Marque du client",
                    "Marca del client",
                    "ブランド",
                    "Client-merk",
                    "Marca do Cliente",
                    "Бренд",
                    "Client Markası",
                    "客户端品牌"),
            List.of(
                    "Sensitivity",
                    "Empfindlichkeit",
                    "Sensibilidad",
                    "Sensibilité",
                    "Sensibilità",
                    "感度",
                    "Gevoeligheid",
                    "Sensibilidade",
                    "Чувствительность",
                    "Hassasiyet",
                    "灵敏度"),
            List.of("Ping", "Latencia", "Latence", "Пинг", "Gecikme", "延迟"),
            List.of("TPS", "ТПС"),
            List.of("Server", "Servidor", "Serveur", "サーバー", "Сервер", "Sunucu", "服务器"),
            List.of("World", "Welt", "Mundo", "Monde", "Mondo", "ワールド", "Wereld", "Мир", "Dünya", "世界"));
    private static final List<String> UNBUNDLED_DISCORD_KEYS = List.of("embed-image-url", "include-verbose");
    private static final String SHIPPED_V3_DISCORD_SENSITIVITY =
            "**xsens**: `%h_sensitivity_raw%` **ysens**: `%v_sensitivity_raw%`";
    private static final String PERCENT_DISCORD_SENSITIVITY =
            "**xsens**: `%h_sensitivity%%` **ysens**: `%v_sensitivity%%`";
    private static final Pattern DISCORD_LABEL = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final List<String> SHIPPED_V4_DISCORD_LAYOUT = List.of(
            "**Version**: `%version%` **Brand**: `%brand%`",
            "**Server**: `%server%` **World**: `%world%` **Location**: `%location%`",
            "**TPS**: `%tps%` **Ping**: `%ping%/%keepalive_ping%` **Clock**: `%clock_behind%ms` **Time**: `%session_time%`",
            PERCENT_DISCORD_SENSITIVITY,
            "**State**: %state%",
            "**Checks**: %checks%",
            "**Verbose**: %verbose%");
    private static final List<Set<List<String>>> SHIPPED_V4_DISCORD_LABELS = List.of(
            Set.of(
                    List.of("Version", "Brand"),
                    List.of("Version", "Marke"),
                    List.of("Versión", "Marca"),
                    List.of("Version", "Marque"),
                    List.of("Versione", "Marca"),
                    List.of("バージョン", "ブランド"),
                    List.of("Versie", "Merk"),
                    List.of("Versão", "Marca"),
                    List.of("Версия", "Бренд"),
                    List.of("Versiyon", "Marka"),
                    List.of("版本", "品牌")),
            Set.of(
                    List.of("Server", "World", "Location"),
                    List.of("Server", "Welt", "Position"),
                    List.of("Servidor", "Mundo", "Ubicación"),
                    List.of("Serveur", "Monde", "Position"),
                    List.of("Server", "Mondo", "Posizione"),
                    List.of("サーバー", "ワールド", "座標"),
                    List.of("Server", "Wereld", "Locatie"),
                    List.of("Servidor", "Mundo", "Localização"),
                    List.of("Сервер", "Мир", "Позиция"),
                    List.of("Sunucu", "Dünya", "Konum"),
                    List.of("服务器", "世界", "位置")),
            Set.of(
                    List.of("TPS", "Ping", "Clock", "Time"),
                    List.of("TPS", "Ping", "Uhr", "Zeit"),
                    List.of("TPS", "Latencia", "Reloj", "Tiempo"),
                    List.of("TPS", "Latence", "Horloge", "Temps"),
                    List.of("TPS", "Ping", "Orologio", "Tempo"),
                    List.of("TPS", "Ping", "クロック", "時間"),
                    List.of("TPS", "Ping", "Klok", "Tijd"),
                    List.of("TPS", "Ping", "Relógio", "Tempo"),
                    List.of("TPS", "Пинг", "Часы", "Время"),
                    List.of("TPS", "Gecikme", "Saat", "Süre"),
                    List.of("TPS", "延迟", "时钟", "时长")),
            Set.of(List.of("xsens", "ysens")),
            Set.of(
                    List.of("State"),
                    List.of("Zustand"),
                    List.of("Estado"),
                    List.of("État"),
                    List.of("Stato"),
                    List.of("状態"),
                    List.of("Status"),
                    List.of("Состояние"),
                    List.of("Durum"),
                    List.of("状态")),
            Set.of(
                    List.of("Checks"),
                    List.of("Prüfungen"),
                    List.of("Comprobaciones"),
                    List.of("Vérifications"),
                    List.of("Controlli"),
                    List.of("違反項目"),
                    List.of("Controles"),
                    List.of("Verificações"),
                    List.of("Проверки"),
                    List.of("Kontroller"),
                    List.of("检查")),
            Set.of(
                    List.of("Verbose"),
                    List.of("Details"),
                    List.of("Detalles"),
                    List.of("Détails"),
                    List.of("Dettagli"),
                    List.of("詳細"),
                    List.of("Detalhes"),
                    List.of("Подробности"),
                    List.of("Ayrıntılar"),
                    List.of("详情")));
    private static final String SHIPPED_V4_DISCORD_FOOTER = "%uuid% v%cult_version%";

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
            List<Object> layout = new ArrayList<>(newContent);
            if (Boolean.FALSE.equals(ctx.input().get("include-state"))) layout.removeIf(line -> uses(line, "%state%"));
            if (Boolean.FALSE.equals(ctx.input().get("include-verbose")))
                layout.removeIf(line -> uses(line, "%verbose%"));
            ctx.output().put("violation-content", layout);
        }
        keepUnbundledDiscordKeys(ctx);
    }

    static void discordValuePerLine(MigrationContext ctx) {
        keepUnbundledDiscordKeys(ctx);
        List<?> content = ctx.output().getList("violation-content");
        Integer version = ctx.input().getInt("config-version");
        if (content != null && (version == null || version < 4) && content.contains(SHIPPED_V3_DISCORD_SENSITIVITY)) {
            List<Object> layout = new ArrayList<>(content);
            layout.replaceAll(line -> SHIPPED_V3_DISCORD_SENSITIVITY.equals(line) ? PERCENT_DISCORD_SENSITIVITY : line);
            ctx.output().put("violation-content", layout);
            content = layout;
        }
        List<?> newContent = ctx.defaults().getList("violation-content");
        if (content != null && newContent != null && isShippedV4DiscordLayout(content)) {
            ctx.output().put("violation-content", new ArrayList<>(newContent));
        }
        String footer = ctx.defaults().getString("embed-footer-text");
        if (footer != null && SHIPPED_V4_DISCORD_FOOTER.equals(ctx.output().getString("embed-footer-text"))) {
            ctx.output().put("embed-footer-text", footer);
        }
    }

    private static boolean isShippedV4DiscordLayout(List<?> content) {
        int next = 0;
        for (int i = 0; i < SHIPPED_V4_DISCORD_LAYOUT.size(); i++) {
            String shipped = SHIPPED_V4_DISCORD_LAYOUT.get(i);
            if (next < content.size() && sameShippedV4Line(content.get(next), i)) {
                next++;
            } else if (!uses(shipped, "%state%") && !uses(shipped, "%verbose%")) {
                return false;
            }
        }
        return next == content.size();
    }

    private static boolean sameShippedV4Line(Object line, int index) {
        if (!(line instanceof String text)) return false;
        Matcher matcher = DISCORD_LABEL.matcher(text);
        List<String> labels = new ArrayList<>();
        StringBuilder skeleton = new StringBuilder();
        while (matcher.find()) {
            labels.add(matcher.group(1));
            matcher.appendReplacement(skeleton, "****");
        }
        matcher.appendTail(skeleton);
        return SHIPPED_V4_DISCORD_LABELS.get(index).contains(labels)
                && skeleton.toString()
                        .equals(DISCORD_LABEL
                                .matcher(SHIPPED_V4_DISCORD_LAYOUT.get(index))
                                .replaceAll("****"));
    }

    private static void keepUnbundledDiscordKeys(MigrationContext ctx) {
        for (String key : UNBUNDLED_DISCORD_KEYS) {
            Object value = ctx.input().get(key);
            if (value != null && ctx.defaults().get(key) == null) ctx.output().put(key, value);
        }
    }

    private static boolean uses(Object line, String placeholder) {
        return line instanceof String text && text.contains(placeholder);
    }

    private static boolean isShippedDiscordContent(List<?> content) {
        if (content.size() == SHIPPED_V1_DISCORD_PLACEHOLDERS.size()) {
            for (int i = 0; i < content.size(); i++) {
                Matcher line = shippedLine(content.get(i));
                if (line == null) return false;
                String placeholder = "%" + SHIPPED_V1_DISCORD_PLACEHOLDERS.get(i) + "%";
                String value = line.group(2);
                if (!placeholder.equals(value) && !("`" + placeholder + "`").equals(value)) return false;
            }
            return true;
        }
        if (content.size() == SHIPPED_V2_DISCORD_VALUES.size()) {
            for (int i = 0; i < content.size(); i++) {
                Matcher line = shippedLine(content.get(i));
                if (line == null
                        || !SHIPPED_V2_DISCORD_LABELS.get(i).contains(line.group(1))
                        || !SHIPPED_V2_DISCORD_VALUES.get(i).equals(line.group(2))) return false;
            }
            return true;
        }
        return false;
    }

    private static @Nullable Matcher shippedLine(Object line) {
        if (!(line instanceof String text)) return null;
        Matcher matcher = SHIPPED_DISCORD_LINE.matcher(text);
        return matcher.matches() ? matcher : null;
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
